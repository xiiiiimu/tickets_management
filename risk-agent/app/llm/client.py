"""Single seam for every LLM call made by any agent. Tests monkeypatch httpx.AsyncClient.post
to drive scripted scenarios without a real network/API key."""
import os
from typing import Any, Optional

import httpx


class LlmUnavailable(RuntimeError):
    """Raised when the LLM cannot be used at all. Callers must degrade, never propagate raw errors."""


async def call_llm(messages: list[dict[str, Any]], tools: Optional[list[dict[str, Any]]] = None) -> dict[str, Any]:
    """Call the configured OpenAI-compatible chat endpoint and return the raw assistant message
    (dict with role/content and, when tools are offered, an optional tool_calls list)."""
    key = os.getenv("OPENAI_API_KEY", "")
    if not key:
        raise LlmUnavailable("LLM API key is not configured")
    base_url = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    model = os.getenv("LLM_MODEL", "gpt-4.1-mini")
    payload: dict[str, Any] = {"model": model, "temperature": 0, "messages": messages}
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    else:
        payload["response_format"] = {"type": "json_object"}
    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            response = await client.post(
                base_url + "/chat/completions",
                headers={"Authorization": "Bearer " + key},
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
            return data["choices"][0]["message"]
    except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError) as exc:
        raise LlmUnavailable(f"LLM call failed: {exc}") from exc
