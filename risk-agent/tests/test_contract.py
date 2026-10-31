import httpx
import pytest

from app.models.contract import Analysis
from conftest import final_message
from helpers import RAW_PAYLOAD, analyst_decision_payload, behavior_evidence_payload, transaction_evidence_payload

ALLOWED_KEYS = {"riskLevel", "scoreAdjustment", "reasons", "suggestion", "confidence", "aiAvailable"}


def test_authentication_required(client, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    assert client.post("/api/risk/analyze", json=RAW_PAYLOAD).status_code == 401


def test_extra_decision_field_is_rejected(client, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    response = client.post(
        "/api/risk/analyze",
        json={**RAW_PAYLOAD, "finalDecision": "PASS"},
        headers={"X-Internal-Key": "test-key"},
    )
    assert response.status_code == 422


def test_no_key_is_explicitly_unavailable(client, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": "test-key"})
    assert response.status_code == 200
    body = response.json()
    assert body["aiAvailable"] is False
    assert body["scoreAdjustment"] == 0


def test_upstream_failure_degrades(client, internal_key, monkeypatch):
    async def fail(*a, **kw):
        raise httpx.ConnectError("offline")

    monkeypatch.setattr(httpx.AsyncClient, "post", fail)
    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": "test-key"})
    assert response.status_code == 200
    assert response.json()["aiAvailable"] is False
    assert response.json()["scoreAdjustment"] == 0


def test_scoreAdjustment_field_is_bounded():
    with pytest.raises(ValueError):
        Analysis(scoreAdjustment=100)


def test_successful_response_exposes_only_the_allowed_java_contract_fields(client, scripted_llm, internal_key):
    scripted_llm.behavior(final_message(behavior_evidence_payload(risk_signal="low", confidence=0.7)))
    scripted_llm.transaction(final_message(transaction_evidence_payload(risk_signal="low", confidence=0.7)))
    scripted_llm.analyst(final_message(analyst_decision_payload(
        status="sufficient", risk_level="MEDIUM", score_adjustment=7, reasons=["elevated but explainable"],
    )))

    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": "test-key"})

    assert response.status_code == 200
    body = response.json()
    assert set(body.keys()) == ALLOWED_KEYS
    assert body["aiAvailable"] is True
    assert body["riskLevel"] == "MEDIUM"
    assert body["scoreAdjustment"] == 7
    assert -20 <= body["scoreAdjustment"] <= 20


def test_out_of_range_adjustment_never_reaches_java_and_degrades_safely(client, scripted_llm, internal_key):
    scripted_llm.behavior(final_message(behavior_evidence_payload()))
    scripted_llm.transaction(final_message(transaction_evidence_payload()))
    # A misbehaving/compromised model tries to push an adjustment far outside the safe range.
    scripted_llm.analyst(final_message(analyst_decision_payload(status="sufficient", score_adjustment=999)))

    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": "test-key"})

    assert response.status_code == 200
    body = response.json()
    assert body["aiAvailable"] is False
    assert body["scoreAdjustment"] == 0


def test_optional_history_does_not_fabricate_observations():
    from app.tools import behavior_tools, transaction_tools
    from helpers import make_request

    for request in (make_request(), make_request(historicalEvents=None, ticketFlows=None, linkedAccounts=None)):
        assert behavior_tools.get_cross_event_behavior(request) == {
            "historyProvided": False, "historicalEvents": [], "distinctEventCount": 0,
        }
        assert behavior_tools.get_account_relationships(request)["relationshipsProvided"] is False
        assert behavior_tools.get_behavior_similarity(request)["comparisons"] == []
        assert transaction_tools.get_ticket_flow_history(request) == {"flowsProvided": False, "ticketFlows": []}
        assert transaction_tools.get_transfer_network(request)["transferCountByTarget"] == {}
    assert behavior_tools.get_cross_event_behavior(make_request(historicalEvents=[]))["historyProvided"] is True


@pytest.mark.parametrize("target,focus", [("behavior", None), ("transaction", " "), (None, "check timing")])
def test_invalid_reinvestigation_degrades_without_uncounted_rounds(client, scripted_llm, internal_key, target, focus):
    scripted_llm.behavior(final_message(behavior_evidence_payload()))
    scripted_llm.transaction(final_message(transaction_evidence_payload()))
    scripted_llm.analyst(final_message(analyst_decision_payload(status="insufficient", target=target, focus=focus)))
    response = client.post("/api/risk/analyze", json=RAW_PAYLOAD, headers={"X-Internal-Key": internal_key})
    assert response.status_code == 200
    assert response.json()["aiAvailable"] is False
    assert response.json()["scoreAdjustment"] == 0
    assert scripted_llm.calls.count("behavior") == 1
    assert scripted_llm.calls.count("transaction") == 1
    assert scripted_llm.calls.count("analyst") == 1
