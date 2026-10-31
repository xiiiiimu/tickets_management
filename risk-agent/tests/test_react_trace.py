"""A deterministic end-to-end demonstration that the ReAct tool loop is really executed, not
just architecturally possible: every tool name in the trace is one the scripted LLM actually
requested and a real tool function actually ran, and every re-investigation genuinely calls
at least one more tool before the analyst concludes.

The scripted LLM only ever decides *which tool to call next* or *when to finish* - it never
supplies tool observation content. All tool results come from the real behavior_tools /
transaction_tools functions reading the same AnalyzeRequest Java would have sent.
"""
import json

from app.graphs.risk_investigation_graph import build_graph
from app.tools import behavior_tools, transaction_tools
from conftest import final_message, tool_call_message
from helpers import analyst_decision_payload, behavior_evidence_payload, make_request, transaction_evidence_payload


def _executed_tool_observations(scripted_llm):
    """Every tool-result message actually sent back to the (scripted) model, decoded from JSON.
    This is the real return value of the real tool function, not something the test injected."""
    return [
        json.loads(message["content"])
        for payload in scripted_llm.payloads
        for message in payload["messages"]
        if message["role"] == "tool"
    ]


async def test_full_react_loop_with_real_tools_and_behavior_reinvestigation(scripted_llm, internal_key):
    request = make_request()

    # BehaviorInvestigationAgent: get_cross_event_behavior -> observation -> get_account_relationships
    # -> observation -> finish (initial pass), then one more real tool call on re-investigation.
    scripted_llm.behavior(
        tool_call_message("get_cross_event_behavior"),
        tool_call_message("get_account_relationships"),
        final_message(behavior_evidence_payload(
            risk_signal="low", confidence=0.35, summary="ambiguous - low confidence versus transaction signal",
        )),
        tool_call_message("get_behavior_similarity"),
        final_message(behavior_evidence_payload(
            risk_signal="high", confidence=0.85, summary="confirmed cross-event account synchronization on re-investigation",
        )),
    )

    # TicketFlowInvestigationAgent: get_ticket_flow_history -> observation -> get_refund_and_admission_pattern
    # -> observation -> finish. Never re-investigated in this scenario.
    scripted_llm.transaction(
        tool_call_message("get_ticket_flow_history"),
        tool_call_message("get_refund_and_admission_pattern"),
        final_message(transaction_evidence_payload(risk_signal="medium", confidence=0.8, summary="mild refund history")),
    )

    # RiskAnalystAgent: sees the conflict (behavior low-confidence vs. transaction medium-signal),
    # sends Behavior back for a focused second look, then concludes once it reports back.
    scripted_llm.analyst(
        final_message(analyst_decision_payload(
            status="insufficient", target="behavior",
            focus="behavior confidence is too low given the transaction signal - confirm cross-event timing",
            risk_level="MEDIUM", score_adjustment=6,
        )),
        final_message(analyst_decision_payload(
            status="sufficient", risk_level="HIGH", score_adjustment=15,
            reasons=["confirmed cross-event account synchronization", "corroborated by transaction refund history"],
        )),
    )

    final_state = await build_graph().ainvoke({"request": request, "reinvestigation_count": 0, "trace": []})
    report = final_state["report"]

    # 1. The trace records the REAL executed tool names (sourced from evidence.toolCalls, which
    #    is built from the tool_calls the scripted model actually issued) - not hand-written text.
    assert report.trace == [
        "behavior:initial:tools=get_cross_event_behavior,get_account_relationships",
        "transaction:initial:tools=get_ticket_flow_history,get_refund_and_admission_pattern",
        "analyst:insufficient:target=behavior",
        "behavior:reinvestigate:tools=get_behavior_similarity",
        "analyst:sufficient:target=-",
    ]
    assert final_state["behavior_evidence"].toolCalls == ["get_behavior_similarity"]
    assert final_state["transaction_evidence"].toolCalls == ["get_ticket_flow_history", "get_refund_and_admission_pattern"]
    assert report.reinvestigationCount == 1
    assert report.riskLevel == "HIGH"

    # 2. Every tool call actually ran the real tool function against the real Java-supplied
    #    context - the observation is not fabricated by the test or the scripted model.
    observations = _executed_tool_observations(scripted_llm)
    assert behavior_tools.get_cross_event_behavior(request) in observations
    assert behavior_tools.get_account_relationships(request) in observations
    assert behavior_tools.get_behavior_similarity(request) in observations
    assert transaction_tools.get_ticket_flow_history(request) in observations
    assert transaction_tools.get_refund_and_admission_pattern(request) in observations
    # The tool that was never called must not have run either.
    assert behavior_tools.get_penalty_history(request) not in observations


async def test_full_react_loop_with_transaction_reinvestigation(scripted_llm, internal_key):
    request = make_request()

    scripted_llm.behavior(
        final_message(behavior_evidence_payload(risk_signal="low", confidence=0.8, summary="clean")),
    )
    scripted_llm.transaction(
        tool_call_message("get_ticket_flow_history"),
        final_message(transaction_evidence_payload(risk_signal="medium", confidence=0.3, summary="unclear transfer destination")),
        tool_call_message("get_transfer_network"),
        final_message(transaction_evidence_payload(risk_signal="high", confidence=0.85, summary="confirmed repeated transfer destinations")),
    )
    scripted_llm.analyst(
        final_message(analyst_decision_payload(
            status="insufficient", target="transaction", focus="confirm repeated transfer destinations",
            risk_level="MEDIUM", score_adjustment=7,
        )),
        final_message(analyst_decision_payload(status="sufficient", risk_level="HIGH", score_adjustment=14)),
    )

    final_state = await build_graph().ainvoke({"request": request, "reinvestigation_count": 0, "trace": []})
    report = final_state["report"]

    assert report.trace == [
        "behavior:initial:tools=none",
        "transaction:initial:tools=get_ticket_flow_history",
        "analyst:insufficient:target=transaction",
        "transaction:reinvestigate:tools=get_transfer_network",
        "analyst:sufficient:target=-",
    ]
    assert final_state["transaction_evidence"].toolCalls == ["get_transfer_network"]
    assert report.reinvestigationCount == 1

    observations = _executed_tool_observations(scripted_llm)
    assert transaction_tools.get_ticket_flow_history(request) in observations
    assert transaction_tools.get_transfer_network(request) in observations


def test_scalper_cluster_http_investigation_uses_real_observations(client, scripted_llm, internal_key):
    from helpers import make_cluster_request

    request = make_cluster_request()
    assert request.features["userMinute"] == 1
    assert request.features["paymentFailures"] == 0

    def observations(payload):
        return {m["name"]: json.loads(m["content"]) for m in payload["messages"] if m["role"] == "tool"}

    def behavior_initial(payload):
        obs = observations(payload)
        history = obs["get_cross_event_behavior"]
        links = obs["get_account_relationships"]
        assert history["distinctEventCount"] == 3
        assert links["linkedAccounts"][0]["relationValue"] == "D1"
        return final_message(behavior_evidence_payload(
            risk_signal="medium", confidence=0.5,
            summary=f"U1/U2 share {links['linkedAccounts'][0]['relationValue']}; participation spans {history['distinctEventCount']} events; timing comparison needed",
        ))

    def transaction_conclusion(payload):
        obs = observations(payload)
        history = obs["get_ticket_flow_history"]["ticketFlows"]
        network = obs["get_transfer_network"]
        assert network["transferCountByTarget"] == {"U8": 2, "U9": 1}
        assert {row["action"] for row in history} == {"PURCHASE", "PAY", "CANCEL", "REFUND", "TRANSFER", "IDENTITY_CHANGE", "ADMISSION"}
        return final_message(transaction_evidence_payload(
            risk_signal="medium", confidence=0.7,
            summary=f"Across E1/E2/E3 observed transfers by target: {network['transferCountByTarget']}; O1/O2/O3 purchase at 12:00, transfer at 12:01, admission at 20:00",
        ))

    def behavior_followup(payload):
        assert "compare U1/U2/U3 timing across events" in payload["messages"][1]["content"]
        obs = observations(payload)
        rows = obs["get_behavior_similarity"]["comparisons"]
        assert obs["get_account_relationships"]["linkedAccounts"][0]["relationValue"] == "D1"
        assert {tuple(row["accountIds"]) for row in rows} == {("U1", "U2"), ("U1", "U3"), ("U2", "U3")}
        max_delta = max(row["absoluteDeltaMs"] for row in rows)
        assert max_delta == 10
        return final_message(behavior_evidence_payload(
            risk_signal="high", confidence=0.8,
            summary=f"U1/U2/U3 across E1/E2/E3: maximum observed timing delta {max_delta} ms; shared D1; legitimate joint attendance remains possible",
        ))

    def analyst_followup(payload):
        text = payload["messages"][1]["content"]
        assert "participation spans 3 events" in text and "U8" in text and "U9" in text
        return final_message(analyst_decision_payload(
            status="insufficient", target="behavior", focus="compare U1/U2/U3 timing across events",
            risk_level="MEDIUM", score_adjustment=5,
        ))

    def analyst_conclusion(payload):
        text = payload["messages"][1]["content"]
        assert "maximum observed timing delta 10 ms" in text
        assert "O1/O2/O3" in text and "U8" in text and "U9" in text
        decision = analyst_decision_payload(
            risk_level="HIGH", score_adjustment=15, confidence=0.75,
            reasons=[
                "hypothesis: coordinated resale activity across E1/E2/E3",
                "supporting_evidence: U1/U2/U3 purchase offsets differ by at most 10 ms; U1/U2 share D1",
                "supporting_evidence: O1/O2/O3 rapidly transfer to U8/U9 with matching admission recipients",
                "contradicting_evidence: legitimate group attendance remains an alternative explanation",
                "missing_evidence: off-platform resale and account ownership are unverified",
                "risk_label: possible_scalper_cluster",
            ],
        )
        decision["suggestion"] = "Investigate possible scalper cluster; validate recipient relationships offline. Java RiskDecisionService retains final decision."
        return final_message(decision)

    scripted_llm.behavior(
        tool_call_message("get_cross_event_behavior", "get_account_relationships"), behavior_initial,
        tool_call_message("get_behavior_similarity", "get_account_relationships"), behavior_followup,
    )
    scripted_llm.transaction(
        tool_call_message("get_ticket_flow_history", "get_transfer_network"), transaction_conclusion,
    )
    scripted_llm.analyst(analyst_followup, analyst_conclusion)
    response = client.post("/api/risk/analyze", json=request.model_dump(), headers={"X-Internal-Key": internal_key})
    assert response.status_code == 200
    body = response.json()
    assert body["aiAvailable"] is True
    assert body["scoreAdjustment"] == 15
    assert "risk_label: possible_scalper_cluster" in body["reasons"]
    assert "Java RiskDecisionService" in body["suggestion"]
    assert "decision" not in body and "finalDecision" not in body
    assert scripted_llm.calls.count("behavior") == 4
    assert scripted_llm.calls.count("transaction") == 2
    assert scripted_llm.calls.count("analyst") == 2
