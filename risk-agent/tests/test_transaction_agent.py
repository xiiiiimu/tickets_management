from app.agents import transaction_agent
from conftest import final_message, tool_call_message
from helpers import make_request, transaction_evidence_payload


async def test_transaction_agent_makes_multiple_tool_calls_before_concluding(scripted_llm, internal_key):
    scripted_llm.transaction(
        tool_call_message("get_ticket_flow_history"),
        tool_call_message("get_transfer_network"),
        final_message(transaction_evidence_payload(
            risk_signal="high", confidence=0.75,
            findings=[{"indicator": "repeated_transfer_recipients", "detail": "transfers repeatedly reach the same accounts", "severity": "high"}],
            summary="repeated transfers across events to fixed recipients",
        )),
    )

    evidence = await transaction_agent.investigate(make_request(), focus=None)

    assert evidence.agent == "transaction"
    assert evidence.toolCalls == ["get_ticket_flow_history", "get_transfer_network"]
    assert evidence.riskSignal == "high"
    assert evidence.findings[0].indicator == "repeated_transfer_recipients"
    assert scripted_llm.calls.count("transaction") == 3


async def test_transaction_agent_uses_the_follow_up_focus(scripted_llm, internal_key):
    scripted_llm.transaction(
        tool_call_message("get_refund_and_admission_pattern"),
        final_message(transaction_evidence_payload(risk_signal="medium", confidence=0.65, summary="repeated refund pattern across events")),
    )

    evidence = await transaction_agent.investigate(make_request(), focus="check refund and admission history")

    assert evidence.riskSignal == "medium"
    assert evidence.toolCalls == ["get_refund_and_admission_pattern"]
    assert "check refund and admission history" in scripted_llm.last_user_message("transaction")


async def test_ticket_lifecycle_observations_preserve_actions_and_destinations(scripted_llm, internal_key):
    import json
    from helpers import make_cluster_request

    request = make_cluster_request()
    scripted_llm.transaction(
        tool_call_message("get_ticket_flow_history", "get_transfer_network", "get_identity_change_history", "get_refund_and_admission_pattern"),
        final_message(transaction_evidence_payload()),
    )
    await transaction_agent.investigate(request)
    observations = {
        m["name"]: json.loads(m["content"])
        for m in scripted_llm.payloads[-1]["messages"] if m["role"] == "tool"
    }
    assert observations["get_ticket_flow_history"]["ticketFlows"] == request.ticketFlows
    network = observations["get_transfer_network"]
    assert network["transferCountByTarget"] == {"U8": 2, "U9": 1}
    assert {f["eventId"] for f in network["transfers"]} == {"E1", "E2", "E3"}
    assert len(observations["get_identity_change_history"]["identityChanges"]) == 3
    assert {f["action"] for f in observations["get_refund_and_admission_pattern"]["refundAndAdmissionEvents"]} == {"CANCEL", "REFUND", "ADMISSION"}
