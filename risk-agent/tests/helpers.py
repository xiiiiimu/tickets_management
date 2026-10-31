from app.models.contract import AnalyzeRequest

RAW_PAYLOAD = dict(
    order={"amount": 380},
    userSummary={"historyCount": 4},
    deviceSummary={"accounts": 1},
    ipSummary={"requests": 3},
    paymentSummary={"failures": 0},
    features={
        "ipMinute": 3, "deviceMinute": 2, "userMinute": 1, "userTenMinutes": 1,
        "deviceAccounts": 1, "ipAccounts": 1, "paymentMinute": 0, "accountAgeHours": 500,
        "historyCount": 4, "cancelRate": 0.0, "refundRate": 0.0, "successOrders": 4,
        "paymentFailures": 0, "paymentAmount": 380, "newDevice": 0,
    },
    matchedRules=[],
)


def make_request(**overrides) -> AnalyzeRequest:
    data = {**RAW_PAYLOAD, **overrides}
    return AnalyzeRequest(**data)


def behavior_evidence_payload(risk_signal="low", confidence=0.6, findings=None, summary="nothing unusual"):
    return {
        "agent": "behavior",
        "findings": findings or [],
        "riskSignal": risk_signal,
        "confidence": confidence,
        "summary": summary,
    }


def transaction_evidence_payload(risk_signal="low", confidence=0.6, findings=None, summary="nothing unusual"):
    return {
        "agent": "transaction",
        "findings": findings or [],
        "riskSignal": risk_signal,
        "confidence": confidence,
        "summary": summary,
    }


def analyst_decision_payload(status="sufficient", target=None, focus=None, risk_level="LOW",
                              score_adjustment=0, reasons=None, confidence=0.7):
    return {
        "status": status,
        "target": target,
        "focus": focus,
        "riskLevel": risk_level,
        "scoreAdjustment": score_adjustment,
        "reasons": reasons or [],
        "suggestion": "monitor",
        "confidence": confidence,
    }


def make_cluster_request():
    """Normal current metrics, but dated multi-account purchases and repeated transfer recipients."""
    events, flows = [], []
    for index, target in enumerate(["U8", "U9", "U8"], start=1):
        event_id, order_id = f"E{index}", f"O{index}"
        day = f"2026-08-0{index}"
        for account, offset in [("U1", 100), ("U2", 105), ("U3", 110)]:
            events.append({
                "accountId": account, "eventId": event_id, "sessionId": f"S{index}",
                "eventTime": f"{day}T12:00:00+08:00", "isHot": True,
                "purchaseResult": "SUCCESS", "ticketCount": 1, "requestOffsetMs": offset,
                "deviceId": "D1" if account in {"U1", "U2"} else "D3", "ip": "192.0.2.1",
                "penaltyFlag": account == "U2" and index == 1,
            })
        for action, time, recipient in [
            ("PURCHASE", "12:00:00", "U1"), ("PAY", "12:00:05", "U1"),
            ("TRANSFER", "12:01:00", target), ("IDENTITY_CHANGE", "12:02:00", target),
            ("ADMISSION", "20:00:00", target),
        ]:
            flows.append({
                "orderId": order_id, "eventId": event_id, "ticketId": f"T{index}",
                "sourceAccountId": "U1", "action": action, "targetAccountId": recipient,
                "actionTime": f"{day}T{time}+08:00",
            })
    flows.extend([
        {"orderId": "O4", "eventId": "E3", "action": "CANCEL", "actionTime": "2026-08-03T13:00:00+08:00"},
        {"orderId": "O4", "eventId": "E3", "action": "REFUND", "actionTime": "2026-08-03T13:05:00+08:00"},
    ])
    return make_request(
        order={"amount": 380, "accountId": "U1"}, historicalEvents=events, ticketFlows=flows,
        linkedAccounts=[{"accountId": "U2", "relationType": "DEVICE", "relationValue": "D1", "strength": 1.0}],
    )
