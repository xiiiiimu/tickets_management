"""LangGraph wiring for the three-agent risk investigation.

    START
      |--> behavior     (BehaviorInvestigationAgent, ReAct tool loop)
      |--> transaction  (TicketFlowInvestigationAgent, ReAct tool loop)
           both join into ->
    analyst  (RiskAnalystAgent: judge sufficiency, or route ONE agent back with a focus)
      |-- sufficient (or MAX_REINVESTIGATIONS reached) --> END
      |-- insufficient, target=behavior     --> behavior --> analyst (loop)
      |-- insufficient, target=transaction  --> transaction --> analyst (loop)

This is a real conditional/looping graph, not a fixed A->B->C call chain: the edge out of
`analyst` is decided at runtime from the analyst's own verdict, and a re-investigated agent
feeds back into the very same `analyst` node.
"""
from typing import Any

from langgraph.graph import END, START, StateGraph

from app.agents import behavior_agent, risk_analyst_agent, transaction_agent
from app.models.investigation import MAX_REINVESTIGATIONS, GraphState, RiskInvestigationReport


async def _behavior_node(state: GraphState) -> dict[str, Any]:
    focus = state.get("behavior_focus")
    evidence = await behavior_agent.investigate(state["request"], focus)
    kind = "reinvestigate" if focus else "initial"
    delta: dict[str, Any] = {
        "behavior_evidence": evidence,
        "trace": [f"behavior:{kind}:tools={','.join(evidence.toolCalls) or 'none'}"],
    }
    if focus:
        delta["reinvestigation_count"] = state.get("reinvestigation_count", 0) + 1
        delta["behavior_focus"] = None
    return delta


async def _transaction_node(state: GraphState) -> dict[str, Any]:
    focus = state.get("transaction_focus")
    evidence = await transaction_agent.investigate(state["request"], focus)
    kind = "reinvestigate" if focus else "initial"
    delta: dict[str, Any] = {
        "transaction_evidence": evidence,
        "trace": [f"transaction:{kind}:tools={','.join(evidence.toolCalls) or 'none'}"],
    }
    if focus:
        delta["reinvestigation_count"] = state.get("reinvestigation_count", 0) + 1
        delta["transaction_focus"] = None
    return delta


async def _analyst_node(state: GraphState) -> dict[str, Any]:
    reinvestigation_count = state.get("reinvestigation_count", 0)
    decision = await risk_analyst_agent.decide(
        state["behavior_evidence"], state["transaction_evidence"], reinvestigation_count
    )
    trace_line = f"analyst:{decision.status}:target={decision.target or '-'}"
    delta: dict[str, Any] = {"analyst_decision": decision, "trace": [trace_line]}

    if decision.status == "insufficient" and decision.target == "behavior":
        delta["behavior_focus"] = decision.focus
    elif decision.status == "insufficient" and decision.target == "transaction":
        delta["transaction_focus"] = decision.focus

    if decision.status == "sufficient" or reinvestigation_count >= MAX_REINVESTIGATIONS:
        delta["report"] = RiskInvestigationReport(
            riskLevel=decision.riskLevel,
            scoreAdjustment=decision.scoreAdjustment,
            reasons=decision.reasons,
            suggestion=decision.suggestion,
            confidence=decision.confidence,
            aiAvailable=True,
            behaviorEvidence=state["behavior_evidence"],
            transactionEvidence=state["transaction_evidence"],
            reinvestigationCount=reinvestigation_count,
            trace=[*state.get("trace", []), trace_line],
        )
    return delta


def _route_after_analyst(state: GraphState) -> str:
    decision = state["analyst_decision"]
    if decision.status == "sufficient":
        return END
    if state.get("reinvestigation_count", 0) >= MAX_REINVESTIGATIONS:
        return END
    if decision.target == "behavior":
        return "behavior"
    if decision.target == "transaction":
        return "transaction"
    return END


def build_graph():
    graph = StateGraph(GraphState)
    graph.add_node("behavior", _behavior_node)
    graph.add_node("transaction", _transaction_node)
    graph.add_node("analyst", _analyst_node)

    graph.add_edge(START, "behavior")
    graph.add_edge(START, "transaction")
    graph.add_edge("behavior", "analyst")
    graph.add_edge("transaction", "analyst")
    graph.add_conditional_edges(
        "analyst",
        _route_after_analyst,
        {"behavior": "behavior", "transaction": "transaction", END: END},
    )
    return graph.compile()


_compiled_graph = None


def get_graph():
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = build_graph()
    return _compiled_graph
