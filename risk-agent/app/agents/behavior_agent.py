"""BehaviorInvestigationAgent: investigates cross-event behavior and account relationships with isolated read-only tools."""
from typing import Optional

from app.agents.base import run_investigator
from app.models.contract import AnalyzeRequest
from app.models.investigation import InvestigationEvidence
from app.prompts.behavior_prompt import BEHAVIOR_SYSTEM_PROMPT, build_user_prompt
from app.tools.behavior_tools import TOOL_SPECS, build_tool_impls

AGENT_NAME = "behavior"


async def investigate(request: AnalyzeRequest, focus: Optional[str] = None) -> InvestigationEvidence:
    return await run_investigator(
        name=AGENT_NAME,
        system_prompt=BEHAVIOR_SYSTEM_PROMPT,
        user_prompt_builder=build_user_prompt,
        tool_specs=TOOL_SPECS,
        tool_impls=build_tool_impls(request),
        request=request,
        focus=focus,
    )
