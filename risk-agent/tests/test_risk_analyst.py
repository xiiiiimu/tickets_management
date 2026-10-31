from app.agents import risk_analyst_agent
from app.models.investigation import InvestigationEvidence
from conftest import final_message
from helpers import analyst_decision_payload, behavior_evidence_payload, transaction_evidence_payload


def _evidence(payload: dict) -> InvestigationEvidence:
    return InvestigationEvidence(**payload, toolCalls=[])


async def test_analyst_concludes_when_evidence_is_consistent(scripted_llm, internal_key):
    scripted_llm.analyst(final_message(analyst_decision_payload(
        status="sufficient", risk_level="LOW", score_adjustment=0, reasons=["no notable signals"],
    )))

    decision = await risk_analyst_agent.decide(
        _evidence(behavior_evidence_payload()), _evidence(transaction_evidence_payload()), reinvestigation_count=0,
    )

    assert decision.status == "sufficient"
    assert decision.target is None
    assert decision.riskLevel == "LOW"


async def test_analyst_can_request_behavior_reinvestigation(scripted_llm, internal_key):
    scripted_llm.analyst(final_message(analyst_decision_payload(
        status="insufficient", target="behavior", focus="confirm device fan-out is not shared household",
        risk_level="MEDIUM", score_adjustment=5,
    )))

    decision = await risk_analyst_agent.decide(
        _evidence(behavior_evidence_payload(risk_signal="medium", confidence=0.3)),
        _evidence(transaction_evidence_payload()),
        reinvestigation_count=0,
    )

    assert decision.status == "insufficient"
    assert decision.target == "behavior"
    assert "device fan-out" in decision.focus


async def test_analyst_can_request_transaction_reinvestigation(scripted_llm, internal_key):
    scripted_llm.analyst(final_message(analyst_decision_payload(
        status="insufficient", target="transaction", focus="confirm transfers converge across events",
        risk_level="MEDIUM", score_adjustment=8,
    )))

    decision = await risk_analyst_agent.decide(
        _evidence(behavior_evidence_payload()),
        _evidence(transaction_evidence_payload(risk_signal="medium", confidence=0.3)),
        reinvestigation_count=0,
    )

    assert decision.status == "insufficient"
    assert decision.target == "transaction"
    assert "converge across events" in decision.focus


async def test_analyst_forces_conclusion_when_no_rounds_remain(scripted_llm, internal_key):
    # The LLM asks for yet another round, but reinvestigation_count already equals the cap.
    scripted_llm.analyst(final_message(analyst_decision_payload(
        status="insufficient", target="behavior", focus="one more look", risk_level="MEDIUM", score_adjustment=5,
    )))

    decision = await risk_analyst_agent.decide(
        _evidence(behavior_evidence_payload()),
        _evidence(transaction_evidence_payload()),
        reinvestigation_count=2,  # == MAX_REINVESTIGATIONS
    )

    assert decision.status == "sufficient"
    assert decision.target is None
    assert decision.focus is None
