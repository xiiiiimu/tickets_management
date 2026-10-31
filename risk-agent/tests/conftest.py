import json as jsonlib

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import app


class FakeResponse:
    def __init__(self, message: dict):
        self._message = message

    def raise_for_status(self):
        return None

    def json(self):
        return {"choices": [{"message": self._message}]}


class ScriptedLLM:
    """Replaces httpx.AsyncClient.post with a per-agent scripted response queue.

    Behavior and Transaction agents run concurrently under LangGraph, so responses are
    routed by inspecting the system prompt marker rather than call order.
    """

    def __init__(self):
        self._scripts: dict[str, list[dict]] = {"behavior": [], "transaction": [], "analyst": []}
        self.calls: list[str] = []
        self.payloads: list[dict] = []

    def behavior(self, *messages: dict) -> "ScriptedLLM":
        self._scripts["behavior"].extend(messages)
        return self

    def transaction(self, *messages: dict) -> "ScriptedLLM":
        self._scripts["transaction"].extend(messages)
        return self

    def analyst(self, *messages: dict) -> "ScriptedLLM":
        self._scripts["analyst"].extend(messages)
        return self

    @staticmethod
    def _identify(payload: dict) -> str:
        system = payload["messages"][0]["content"]
        if system.startswith("You are the Risk Analyst"):
            return "analyst"
        if system.startswith("You are the BehaviorInvestigationAgent"):
            return "behavior"
        if system.startswith("You are the TicketFlowInvestigationAgent"):
            return "transaction"
        raise AssertionError(f"unrecognized system prompt: {system[:120]}")

    async def __call__(self, url, *, headers=None, json=None, **kwargs):
        identifier = self._identify(json)
        self.calls.append(identifier)
        self.payloads.append(json)
        queue = self._scripts[identifier]
        if not queue:
            raise AssertionError(f"no more scripted LLM responses for '{identifier}'")
        message = queue.pop(0)
        return FakeResponse(message(json) if callable(message) else message)

    def last_user_message(self, identifier: str) -> str:
        for payload in reversed(self.payloads):
            if self._identify(payload) == identifier:
                user_messages = [m["content"] for m in payload["messages"] if m["role"] == "user"]
                return user_messages[-1]
        raise AssertionError(f"no calls recorded for '{identifier}'")


def tool_call_message(*tool_names: str) -> dict:
    return {
        "role": "assistant",
        "content": "",
        "tool_calls": [
            {"id": f"call_{i}", "type": "function", "function": {"name": name, "arguments": "{}"}}
            for i, name in enumerate(tool_names)
        ],
    }


def final_message(payload: dict) -> dict:
    return {"role": "assistant", "content": jsonlib.dumps(payload)}


@pytest.fixture
def scripted_llm(monkeypatch):
    llm = ScriptedLLM()
    monkeypatch.setattr(httpx.AsyncClient, "post", llm)
    return llm


@pytest.fixture
def internal_key(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_KEY", "test-key")
    monkeypatch.setenv("OPENAI_API_KEY", "test-llm-key")
    return "test-key"


@pytest.fixture
def client():
    return TestClient(app)
