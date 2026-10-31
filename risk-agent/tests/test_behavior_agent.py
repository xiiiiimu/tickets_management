from app.agents import behavior_agent
from conftest import final_message, tool_call_message
from helpers import behavior_evidence_payload, make_request


async def test_behavior_agent_makes_multiple_tool_calls_before_concluding(scripted_llm, internal_key):
    scripted_llm.behavior(
        tool_call_message("get_cross_event_behavior"),
        tool_call_message("get_account_relationships"),
        final_message(behavior_evidence_payload(
            risk_signal="medium", confidence=0.7,
            findings=[{"indicator": "device_fanout", "detail": "5 accounts on one device", "severity": "medium"}],
            summary="elevated device fan-out",
        )),
    )

    evidence = await behavior_agent.investigate(make_request(), focus=None)

    assert evidence.agent == "behavior"
    assert evidence.toolCalls == ["get_cross_event_behavior", "get_account_relationships"]
    assert evidence.riskSignal == "medium"
    assert evidence.findings[0].indicator == "device_fanout"
    assert scripted_llm.calls.count("behavior") == 3


async def test_behavior_agent_uses_the_follow_up_focus(scripted_llm, internal_key):
    scripted_llm.behavior(
        tool_call_message("get_behavior_similarity"),
        final_message(behavior_evidence_payload(risk_signal="high", confidence=0.8, summary="synchronized accounts across events")),
    )

    evidence = await behavior_agent.investigate(make_request(), focus="check cross-event timing pattern")

    assert evidence.riskSignal == "high"
    assert evidence.toolCalls == ["get_behavior_similarity"]
    assert evidence.confidence == 0.8
    assert "check cross-event timing pattern" in scripted_llm.last_user_message("behavior")


async def test_cross_event_observations_include_account_timing_and_penalties(scripted_llm, internal_key):
    import json
    from app.tools import behavior_tools
    from helpers import make_cluster_request

    request = make_cluster_request()
    scripted_llm.behavior(
        tool_call_message("get_cross_event_behavior", "get_account_relationships", "get_behavior_similarity", "get_penalty_history"),
        final_message(behavior_evidence_payload()),
    )
    await behavior_agent.investigate(request)
    observations = {
        m["name"]: json.loads(m["content"])
        for m in scripted_llm.payloads[-1]["messages"] if m["role"] == "tool"
    }
    history = observations["get_cross_event_behavior"]
    assert history["historicalEvents"] == request.historicalEvents
    assert history["distinctEventCount"] == 3
    assert observations["get_account_relationships"]["linkedAccounts"] == request.linkedAccounts
    comparisons = observations["get_behavior_similarity"]["comparisons"]
    assert len(comparisons) == 9
    assert {c["eventId"] for c in comparisons} == {"E1", "E2", "E3"}
    assert all(c["absoluteDeltaMs"] == 5 for c in comparisons if c["accountIds"] == ["U1", "U2"])
    assert observations["get_penalty_history"]["penaltyEvents"] == [request.historicalEvents[1]]

    # Different sessions must not be compared; missing account IDs cannot be inferred.
    partial = make_request(historicalEvents=[
        {"eventId": "E1", "sessionId": "S1", "accountId": "U1", "requestOffsetMs": 10},
        {"eventId": "E1", "sessionId": "S2", "accountId": "U2", "requestOffsetMs": 10},
        {"eventId": "E1", "sessionId": "S1", "requestOffsetMs": 10},
    ])
    result = behavior_tools.get_behavior_similarity(partial)
    assert result["comparisons"] == []
    assert result["excludedRecordCount"] == 1
