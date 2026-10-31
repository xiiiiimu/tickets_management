from app.graphs.risk_investigation_graph import build_graph
from conftest import final_message
from helpers import analyst_decision_payload, behavior_evidence_payload, make_request, transaction_evidence_payload


async def _run(state_overrides=None):
    graph = build_graph()
    initial_state = {"request": make_request(), "reinvestigation_count": 0, "trace": []}
    initial_state.update(state_overrides or {})
    return await graph.ainvoke(initial_state)


async def test_graph_finishes_without_reinvestigation_when_evidence_is_consistent(scripted_llm, internal_key):
    scripted_llm.behavior(final_message(behavior_evidence_payload(risk_signal="low", confidence=0.8)))
    scripted_llm.transaction(final_message(transaction_evidence_payload(risk_signal="low", confidence=0.8)))
    scripted_llm.analyst(final_message(analyst_decision_payload(status="sufficient", risk_level="LOW")))

    final_state = await _run()

    report = final_state["report"]
    assert report.aiAvailable is True
    assert report.reinvestigationCount == 0
    assert scripted_llm.calls.count("behavior") == 1
    assert scripted_llm.calls.count("transaction") == 1
    assert scripted_llm.calls.count("analyst") == 1
    assert any(line.startswith("behavior:initial") for line in report.trace)
    assert any(line.startswith("transaction:initial") for line in report.trace)
    assert not any("reinvestigate" in line for line in report.trace)


async def test_graph_triggers_behavior_reinvestigation_then_finishes(scripted_llm, internal_key):
    scripted_llm.behavior(
        final_message(behavior_evidence_payload(risk_signal="medium", confidence=0.3, summary="ambiguous device signal")),
        final_message(behavior_evidence_payload(risk_signal="high", confidence=0.85, summary="confirmed device farm")),
    )
    scripted_llm.transaction(final_message(transaction_evidence_payload(risk_signal="low", confidence=0.8)))
    scripted_llm.analyst(
        final_message(analyst_decision_payload(
            status="insufficient", target="behavior", focus="confirm device farm", risk_level="MEDIUM", score_adjustment=5,
        )),
        final_message(analyst_decision_payload(status="sufficient", risk_level="HIGH", score_adjustment=15)),
    )

    final_state = await _run()

    report = final_state["report"]
    assert report.reinvestigationCount == 1
    assert report.riskLevel == "HIGH"
    assert scripted_llm.calls.count("behavior") == 2
    assert scripted_llm.calls.count("transaction") == 1
    assert scripted_llm.calls.count("analyst") == 2
    assert final_state["transaction_evidence"].summary == "nothing unusual"  # untouched by the re-investigation
    assert [line for line in report.trace if line.startswith("behavior:")] == [
        "behavior:initial:tools=none",
        "behavior:reinvestigate:tools=none",
    ]


async def test_graph_triggers_transaction_reinvestigation_then_finishes(scripted_llm, internal_key):
    scripted_llm.behavior(final_message(behavior_evidence_payload(risk_signal="low", confidence=0.8)))
    scripted_llm.transaction(
        final_message(transaction_evidence_payload(risk_signal="medium", confidence=0.3, summary="unclear refund pattern")),
        final_message(transaction_evidence_payload(risk_signal="high", confidence=0.85, summary="confirmed refund abuse")),
    )
    scripted_llm.analyst(
        final_message(analyst_decision_payload(
            status="insufficient", target="transaction", focus="confirm refund abuse", risk_level="MEDIUM", score_adjustment=6,
        )),
        final_message(analyst_decision_payload(status="sufficient", risk_level="HIGH", score_adjustment=12)),
    )

    final_state = await _run()

    report = final_state["report"]
    assert report.reinvestigationCount == 1
    assert report.riskLevel == "HIGH"
    assert scripted_llm.calls.count("transaction") == 2
    assert scripted_llm.calls.count("behavior") == 1
    assert scripted_llm.calls.count("analyst") == 2
    assert [line for line in report.trace if line.startswith("transaction:")] == [
        "transaction:initial:tools=none",
        "transaction:reinvestigate:tools=none",
    ]


async def test_graph_caps_reinvestigation_at_two_rounds(scripted_llm, internal_key):
    scripted_llm.behavior(
        final_message(behavior_evidence_payload(risk_signal="medium", confidence=0.3)),
        final_message(behavior_evidence_payload(risk_signal="medium", confidence=0.35)),
        final_message(behavior_evidence_payload(risk_signal="medium", confidence=0.4)),
    )
    scripted_llm.transaction(final_message(transaction_evidence_payload(risk_signal="low", confidence=0.8)))
    # The analyst asks for yet another round every single time - the graph, not the model, must stop it.
    scripted_llm.analyst(
        final_message(analyst_decision_payload(status="insufficient", target="behavior", focus="round 1", score_adjustment=5)),
        final_message(analyst_decision_payload(status="insufficient", target="behavior", focus="round 2", score_adjustment=5)),
        final_message(analyst_decision_payload(status="insufficient", target="behavior", focus="round 3 - should never be used", score_adjustment=5)),
    )

    final_state = await _run()

    report = final_state["report"]
    assert report.reinvestigationCount == 2
    assert scripted_llm.calls.count("analyst") == 3  # capped: it is asked once more, then forced to conclude
    assert scripted_llm.calls.count("behavior") == 3  # initial + 2 reinvestigations, never a 3rd
    assert report.aiAvailable is True  # forced conclusion is still a valid, safe report
