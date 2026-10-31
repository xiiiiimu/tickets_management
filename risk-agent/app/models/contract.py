"""Wire contract with the Java caller (com.ticketing.risk.RiskAgentClient). Do not change field names or types."""
from typing import Any
from pydantic import BaseModel, Field, ConfigDict


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    order: dict[str, Any]
    userSummary: dict[str, Any]
    deviceSummary: dict[str, Any]
    ipSummary: dict[str, Any]
    paymentSummary: dict[str, Any]
    features: dict[str, float]
    matchedRules: list[str]
    # Absence is missing evidence, not low risk. Existing requests remain valid.
    historicalEvents: list[dict[str, Any]] | None = None
    ticketFlows: list[dict[str, Any]] | None = None
    linkedAccounts: list[dict[str, Any]] | None = None


class Analysis(BaseModel):
    model_config = ConfigDict(extra="forbid")
    riskLevel: str = "UNAVAILABLE"
    scoreAdjustment: int = Field(default=0, ge=-20, le=20)
    reasons: list[str] = Field(default_factory=list, max_length=20)
    suggestion: str = "Java rules remain authoritative"
    confidence: float = Field(default=0, ge=0, le=1)
    aiAvailable: bool = False
