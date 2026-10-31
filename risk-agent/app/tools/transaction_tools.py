"""Read-only ticket lifecycle observations for TicketFlowInvestigationAgent.

The transaction module/node name is retained for graph and tracing compatibility.
"""
from collections import Counter
from typing import Any, Callable

from app.models.contract import AnalyzeRequest


def get_ticket_flow_history(request: AnalyzeRequest) -> dict[str, Any]:
    """Read purchase/payment/cancel/refund/transfer/identity/admission rows with order/event times."""
    return {"flowsProvided": request.ticketFlows is not None, "ticketFlows": request.ticketFlows or []}


def get_transfer_network(request: AnalyzeRequest) -> dict[str, Any]:
    """Read transfer edges and observed destination counts across events; not verified final owners."""
    transfers = [flow for flow in request.ticketFlows or [] if flow.get("action") == "TRANSFER"]
    counts = Counter(str(flow["targetAccountId"]) for flow in transfers if flow.get("targetAccountId") is not None)
    return {
        "flowsProvided": request.ticketFlows is not None,
        "transfers": transfers, "transferCountByTarget": dict(counts),
        "missingTargetCount": sum(flow.get("targetAccountId") is None for flow in transfers),
        "scope": "Counts of supplied transfer records, not ticket quantities or confirmed ultimate owners; follow lifecycle evidence.",
    }


def get_identity_change_history(request: AnalyzeRequest) -> dict[str, Any]:
    """Read dated identity changes and target accounts for comparison with purchase and admission."""
    return {
        "flowsProvided": request.ticketFlows is not None,
        "identityChanges": [flow for flow in request.ticketFlows or [] if flow.get("action") == "IDENTITY_CHANGE"],
    }


def get_refund_and_admission_pattern(request: AnalyzeRequest) -> dict[str, Any]:
    """Read cancellation/refund/admission records across orders/events, without judging a ratio."""
    return {
        "flowsProvided": request.ticketFlows is not None,
        "refundAndAdmissionEvents": [
            flow for flow in request.ticketFlows or [] if flow.get("action") in {"CANCEL", "REFUND", "ADMISSION"}
        ],
    }


TOOL_FUNCTIONS: dict[str, Callable[[AnalyzeRequest], dict[str, Any]]] = {
    "get_ticket_flow_history": get_ticket_flow_history,
    "get_transfer_network": get_transfer_network,
    "get_identity_change_history": get_identity_change_history,
    "get_refund_and_admission_pattern": get_refund_and_admission_pattern,
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
