"""RiskAnalystAgent: reads both investigators' evidence, judges whether it is sufficient and
consistent, and either concludes or sends exactly one investigator back for a focused
re-investigation. Never issues a PASS/REVIEW/BLOCK decision - only an advisory, bounded
scoreAdjustment that the Java rule engine may use as a small nudge."""
from app.llm.client import LlmUnavailable, call_llm
from app.models.investigation import MAX_REINVESTIGATIONS, AnalystDecision, InvestigationEvidence
from app.prompts.analyst_prompt import ANALYST_SYSTEM_PROMPT, build_user_prompt


async def decide(
    behavior_evidence: InvestigationEvidence,
    transaction_evidence: InvestigationEvidence,
    reinvestigation_count: int,
) -> AnalystDecision:
    remaining = max(0, MAX_REINVESTIGATIONS - reinvestigation_count)
    messages = [
        {"role": "system", "content": ANALYST_SYSTEM_PROMPT},
        {"role": "user", "content": build_user_prompt(
            behavior_evidence.model_dump_json(),
            transaction_evidence.model_dump_json(),
            reinvestigation_count,
            remaining,
        )},
    ]
    message = await call_llm(messages, tools=None)
    content = message.get("content")
    if not content:
        raise LlmUnavailable("risk analyst returned an empty decision")
    decision = AnalystDecision.model_validate_json(content)
    if remaining <= 0 and decision.status == "insufficient":
        # Hard safety net: the graph already caps re-investigation rounds, but the decision
        # object itself must not claim it wants another round it will never get.
        decision = decision.model_copy(update={"status": "sufficient", "target": None, "focus": None})
    elif decision.status == "insufficient" and (decision.target is None or not decision.focus or not decision.focus.strip()):
        # Graph rounds are counted on a focused revisit. Reject an empty focus rather than
        # routing an uncounted revisit that could bypass MAX_REINVESTIGATIONS.
        raise LlmUnavailable("risk analyst requested reinvestigation without a target/focus")
    return decision
