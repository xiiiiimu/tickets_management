"""Read-only longitudinal observations for BehaviorInvestigationAgent."""
from collections import defaultdict
from itertools import combinations
from math import isfinite
from typing import Any, Callable

from app.models.contract import AnalyzeRequest


def get_cross_event_behavior(request: AnalyzeRequest) -> dict[str, Any]:
    """Read dated event/session participation, purchase outcomes, offsets and device/IP facts."""
    events = request.historicalEvents or []
    return {
        "historyProvided": request.historicalEvents is not None,
        "historicalEvents": events,
        "distinctEventCount": len({str(e["eventId"]) for e in events if e.get("eventId") is not None}),
    }


def get_account_relationships(request: AnalyzeRequest) -> dict[str, Any]:
    """Read supplied DEVICE/IP/PAYMENT links; a shared relation alone does not prove coordination."""
    return {
        "relationshipsProvided": request.linkedAccounts is not None,
        "subjectAccountId": request.order.get("accountId"),
        "linkedAccounts": request.linkedAccounts or [],
    }


def get_behavior_similarity(request: AnalyzeRequest) -> dict[str, Any]:
    """Compare explicit account offsets within the same event/session; report deltas, not risk."""
    grouped = defaultdict(lambda: defaultdict(list))
    excluded = 0
    for event in request.historicalEvents or []:
        account, event_id, offset = event.get("accountId"), event.get("eventId"), event.get("requestOffsetMs")
        if account is None or event_id is None or type(offset) not in (int, float) or not isfinite(offset):
            excluded += 1
            continue
        # Earliest observed attempt per account/session avoids counting retries as overlap.
        grouped[(str(event_id), event.get("sessionId"))][str(account)].append(offset)
    comparisons = []
    for (event_id, session_id), accounts in grouped.items():
        for left, right in combinations(sorted(accounts), 2):
            left_offset, right_offset = min(accounts[left]), min(accounts[right])
            comparisons.append({
                "eventId": event_id, "sessionId": session_id,
                "accountIds": [left, right], "requestOffsetsMs": [left_offset, right_offset],
                "absoluteDeltaMs": abs(left_offset - right_offset),
            })
    return {
        "historyProvided": request.historicalEvents is not None,
        "comparisons": comparisons, "excludedRecordCount": excluded,
        "comparisonScope": "Same supplied event/session only; no sessionId means session alignment is unverified.",
    }


def get_penalty_history(request: AnalyzeRequest) -> dict[str, Any]:
    """Read dated penalties and count historical records that omit penalty information."""
    events = request.historicalEvents or []
    return {
        "historyProvided": request.historicalEvents is not None,
        "penaltyEvents": [event for event in events if event.get("penaltyFlag") is True],
        "unknownPenaltyRecordCount": sum(event.get("penaltyFlag") is None for event in events),
    }


TOOL_FUNCTIONS: dict[str, Callable[[AnalyzeRequest], dict[str, Any]]] = {
    "get_cross_event_behavior": get_cross_event_behavior,
    "get_account_relationships": get_account_relationships,
    "get_behavior_similarity": get_behavior_similarity,
    "get_penalty_history": get_penalty_history,
}

TOOL_SPECS: list[dict[str, Any]] = [
    {"type": "function", "function": {
        "name": name, "description": fn.__doc__,
        "parameters": {"type": "object", "properties": {}, "required": []},
    }}
    for name, fn in TOOL_FUNCTIONS.items()
]


def build_tool_impls(request: AnalyzeRequest) -> dict[str, Callable[[], dict[str, Any]]]:
    return {name: (lambda fn=fn: fn(request)) for name, fn in TOOL_FUNCTIONS.items()}
