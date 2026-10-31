from app.tools import behavior_tools, transaction_tools
from helpers import make_request


def test_behavior_and_transaction_tool_sets_do_not_overlap():
    behavior_names = {spec["function"]["name"] for spec in behavior_tools.TOOL_SPECS}
    transaction_names = {spec["function"]["name"] for spec in transaction_tools.TOOL_SPECS}

    assert behavior_names, "behavior tool set must not be empty"
    assert transaction_names, "transaction tool set must not be empty"
    assert behavior_names.isdisjoint(transaction_names)


def test_behavior_agent_cannot_reach_transaction_tools():
    impls = behavior_tools.build_tool_impls(make_request())
    assert set(impls.keys()) == set(behavior_tools.TOOL_FUNCTIONS.keys())
    for transaction_only in transaction_tools.TOOL_FUNCTIONS:
        assert transaction_only not in impls


def test_transaction_agent_cannot_reach_behavior_tools():
    impls = transaction_tools.build_tool_impls(make_request())
    assert set(impls.keys()) == set(transaction_tools.TOOL_FUNCTIONS.keys())
    for behavior_only in behavior_tools.TOOL_FUNCTIONS:
        assert behavior_only not in impls


def test_each_investigator_has_four_fact_tools():
    for module in (behavior_tools, transaction_tools):
        assert len(module.TOOL_FUNCTIONS) == 4
        assert {spec["function"]["name"] for spec in module.TOOL_SPECS} == set(module.TOOL_FUNCTIONS)
