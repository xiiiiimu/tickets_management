"""Internal investigation data structures. None of this crosses the wire to Java directly;
RiskAnalystAgent projects its conclusion into the app.models.contract.Analysis shape."""
from typing import Literal, Optional, TypedDict, Annotated
import operator
from pydantic import BaseModel, Field, ConfigDict

from app.models.contract import AnalyzeRequest

MAX_TOOL_ITERATIONS = 4
MAX_REINVESTIGATIONS = 2

AgentName = Literal["behavior", "transaction"]
Severity = Literal["low", "medium", "high"]
RiskSignal = Literal["low", "medium", "high"]


class AgentFinding(BaseModel):
    model_config = ConfigDict(extra="forbid")
    indicator: str
    detail: str
    severity: Severity = "low"


class InvestigationEvidence(BaseModel):
    """Structured conclusion an investigator agent produces after its tool-call loop."""
    model_config = ConfigDict(extra="forbid")
    agent: AgentName
    findings: list[AgentFinding] = Field(default_factory=list)
    riskSignal: RiskSignal = "low"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    summary: str = ""
    toolCalls: list[str] = Field(default_factory=list)
    available: bool = True


class AnalystDecision(BaseModel):
    """RiskAnalystAgent's verdict for one analyst pass."""
    model_config = ConfigDict(extra="forbid")
    status: Literal["sufficient", "insufficient"] = "sufficient"
    target: Optional[AgentName] = None
    focus: Optional[str] = None
    riskLevel: Literal["LOW", "MEDIUM", "HIGH"] = "LOW"
    scoreAdjustment: int = Field(default=0, ge=-20, le=20)
    reasons: list[str] = Field(default_factory=list, max_length=20)
    suggestion: str = "Java rules remain authoritative"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class RiskInvestigationReport(BaseModel):
    """Full internal report kept for tracing/debugging. Only a subset is exposed to Java."""
    model_config = ConfigDict(extra="forbid")
    riskLevel: Literal["LOW", "MEDIUM", "HIGH", "UNAVAILABLE"] = "UNAVAILABLE"
    scoreAdjustment: int = Field(default=0, ge=-20, le=20)
    reasons: list[str] = Field(default_factory=list)
    suggestion: str = "Java rules remain authoritative"
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    aiAvailable: bool = False
    behaviorEvidence: Optional[InvestigationEvidence] = None
    transactionEvidence: Optional[InvestigationEvidence] = None
    reinvestigationCount: int = 0
    trace: list[str] = Field(default_factory=list)


class GraphState(TypedDict, total=False):
    request: AnalyzeRequest
    behavior_evidence: Optional[InvestigationEvidence]
    transaction_evidence: Optional[InvestigationEvidence]
    behavior_focus: Optional[str]
    transaction_focus: Optional[str]
    reinvestigation_count: int
    analyst_decision: Optional[AnalystDecision]
    report: Optional[RiskInvestigationReport]
    trace: Annotated[list[str], operator.add]
