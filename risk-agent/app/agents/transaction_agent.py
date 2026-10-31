"""TicketFlowInvestigationAgent: investigates ticket lifecycle and transfer relationships with isolated read-only tools."""
from typing import Optional

from app.agents.base import run_investigator
from app.models.contract import AnalyzeRequest
from app.models.investigation import InvestigationEvidence
from app.prompts.transaction_prompt import TRANSACTION_SYSTEM_PROMPT, build_user_prompt
from app.tools.transaction_tools import TOOL_SPECS, build_tool_impls

AGENT_NAME = "transaction"


async def investigate(request: AnalyzeRequest, focus: Optional[str] = None) -> InvestigationEvidence:
    return await run_investigator(
        name=AGENT_NAME,
        system_prompt=TRANSACTION_SYSTEM_PROMPT,
        user_prompt_builder=build_user_prompt,
        tool_specs=TOOL_SPECS,
        tool_impls=build_tool_impls(request),
        request=request,
        focus=focus,
    )
