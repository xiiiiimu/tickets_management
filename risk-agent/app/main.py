"""Auxiliary risk analysis. This process has no order/database write credentials.

Orchestrates a three-agent LangGraph investigation (BehaviorInvestigationAgent, TicketFlowInvestigationAgent,
RiskAnalystAgent - see app/graphs/risk_investigation_graph.py) but exposes exactly the same
wire contract Java has always called: POST /api/risk/analyze -> Analysis. Any failure anywhere
in the agent pipeline degrades to aiAvailable=false / scoreAdjustment=0 and never raises past
this boundary, so it can never affect the Java main path.
"""
import os
import secrets

from fastapi import FastAPI, Header, HTTPException

from app.graphs.risk_investigation_graph import get_graph
from app.models.contract import Analysis, AnalyzeRequest

app = FastAPI(title="Risk analysis assistant")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/api/risk/analyze", response_model=Analysis)
async def analyze(request: AnalyzeRequest, x_internal_key: str = Header(default="")):
    expected = os.getenv("INTERNAL_API_KEY", "")
    if not expected or not secrets.compare_digest(x_internal_key, expected):
        raise HTTPException(401, "Internal authentication required")

    if not os.getenv("OPENAI_API_KEY", ""):
        return Analysis(reasons=["LLM API key is not configured"])

    try:
        initial_state = {"request": request, "reinvestigation_count": 0, "trace": []}
        final_state = await get_graph().ainvoke(initial_state)
        report = final_state.get("report")
        if report is None:
            raise RuntimeError("investigation graph finished without a report")
        return Analysis(
            riskLevel=report.riskLevel,
            scoreAdjustment=report.scoreAdjustment,
            reasons=report.reasons,
            suggestion=report.suggestion,
            confidence=report.confidence,
            aiAvailable=report.aiAvailable,
        )
    except Exception:
        # Any agent/LLM/graph failure must never reach Java as an error and must never
        # contribute a non-zero adjustment - only the rule engine's own score matters then.
        return Analysis(reasons=["LLM unavailable or response failed validation"])
