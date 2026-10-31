"""Verifies the ReAct tool-call loop has a hard, enforced ceiling: a model that keeps asking
for another tool call forever must be cut off at MAX_TOOL_ITERATIONS, never loop forever, and
the failure must degrade the whole pipeline safely rather than crash or hang."""
import pytest

from app.agents import behavior_agent, transaction_agent
from app.llm.client import LlmUnavailable
from app.models.investigation import MAX_TOOL_ITERATIONS
from conftest import final_message, tool_call_message
from helpers import RAW_PAYLOAD, make_request, transaction_evidence_payload


async def test_behavior_agent_is_forcibly_stopped_after_max_tool_iterations(scripted_llm, internal_key):
    # The model never gives a final answer - it keeps requesting another tool call every turn.
    scripted_llm.behavior(*[tool_call_message("get_cross_event_behavior") for _ in range(MAX_TOOL_ITERATIONS)])

    with pytest.raises(LlmUnavailable):
        await behavior_agent.investigate(make_request(), focus=None)

    # Exactly MAX_TOOL_ITERATIONS calls happened: not fewer (didn't bail early) and not more
    # (no infinite loop) - the scripted queue would raise AssertionError on a 5th call if the
    # loop kept going, which did not happen.
    assert scripted_llm.calls.count("behavior") == MAX_TOOL_ITERATIONS


async def test_transaction_agent_is_forcibly_stopped_after_max_tool_iterations(scripted_llm, internal_key):
    scripted_llm.transaction(*[tool_call_message("get_ticket_flow_history") for _ in range(MAX_TOOL_ITERATIONS)])

    with pytest.raises(LlmUnavailable):
        await transaction_agent.investigate(make_request(), focus=None)

    assert scripted_llm.calls.count("transaction") == MAX_TOOL_ITERATIONS


async def test_pipeline_degrades_safely_when_an_agent_exceeds_the_tool_iteration_cap(client, scripted_llm, internal_key):
    scripted_llm.behavior(*[tool_call_message("get_cross_event_behavior") for _ in range(MAX_TOOL_ITERATIONS)])
    scripted_llm.transaction(final_message(transaction_evidence_payload()))

    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": "test-key"})

    assert response.status_code == 200
    body = response.json()
    assert body["aiAvailable"] is False
    assert body["scoreAdjustment"] == 0
    assert scripted_llm.calls.count("behavior") == MAX_TOOL_ITERATIONS
