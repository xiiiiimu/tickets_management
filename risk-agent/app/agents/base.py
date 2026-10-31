"""Shared ReAct-style tool-call loop used by both investigator agents. Each investigator keeps
its own independent prompt and tool set (see behavior_agent.py / transaction_agent.py); this module
only factors out the mechanical loop so the two agents are not duplicated line-for-line."""
import json
from typing import Any, Callable, Optional

from app.llm.client import LlmUnavailable, call_llm
from app.models.contract import AnalyzeRequest
from app.models.investigation import MAX_TOOL_ITERATIONS, AgentName, InvestigationEvidence


async def run_investigator(
    name: AgentName,
    system_prompt: str,
    user_prompt_builder: Callable[[str, Optional[str]], str],
    tool_specs: list[dict[str, Any]],
    tool_impls: dict[str, Callable[[], dict[str, Any]]],
    request: AnalyzeRequest,
    focus: Optional[str],
) -> InvestigationEvidence:
    context_summary = json.dumps({"orderAmount": request.order.get("amount")})
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt_builder(context_summary, focus)},
    ]
    tool_call_trace: list[str] = []
    for _ in range(MAX_TOOL_ITERATIONS):
        message = await call_llm(messages, tools=tool_specs)
        tool_calls = message.get("tool_calls")
        if tool_calls:
            messages.append({
                "role": "assistant",
                "content": message.get("content") or "",
                "tool_calls": tool_calls,
            })
            for call in tool_calls:
                fn_name = call["function"]["name"]
                impl = tool_impls.get(fn_name)
                result = impl() if impl is not None else {"error": f"unknown tool {fn_name}"}
                tool_call_trace.append(fn_name)
                messages.append({
                    "role": "tool",
                    "tool_call_id": call.get("id", fn_name),
                    "name": fn_name,
                    "content": json.dumps(result),
                })
            continue
        content = message.get("content")
        if not content:
            raise LlmUnavailable(f"{name} agent returned an empty final answer")
        evidence = InvestigationEvidence.model_validate_json(content)
        evidence = evidence.model_copy(update={"toolCalls": tool_call_trace})
        return evidence
    raise LlmUnavailable(f"{name} agent exceeded tool-loop iterations ({MAX_TOOL_ITERATIONS}) without a conclusion")
