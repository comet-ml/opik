#!/usr/bin/env python3
"""
Live smoke test for the OrcaRouter LLM provider (https://www.orcarouter.ai).

OrcaRouter is OpenAI-compatible, so this script uses the official `openai` Python
SDK pointed at the OrcaRouter base URL. It exercises the request shapes Opik relies
on: non-streaming chat, streaming chat, tool calling, an auth failure (bad key), and
a wrong-model error.

Setup:
  1. Get an API key at https://www.orcarouter.ai/console (keys start with "sk-orca-").
  2. export ORCAROUTER_API_KEY="sk-orca-..."
  3. pip install openai
  4. python scripts/orcarouter-live-test.py

Notes:
  - The `orcarouter/auto` router is a reasoning model; with a tiny max_tokens it can
    return empty content, so the auto case uses max_tokens >= 256.
  - Namespaced upstreams (e.g. openai/gpt-4o-mini) are sent as-is; the bare router
    alias "orcarouter/auto" must keep its prefix (the API rejects a bare "auto").
"""

import os
import sys

BASE_URL = "https://api.orcarouter.ai/v1"

try:
    from openai import OpenAI
    from openai import APIStatusError, AuthenticationError
except ImportError:
    print("The 'openai' package is required: pip install openai", file=sys.stderr)
    sys.exit(2)


def _client(api_key):
    return OpenAI(base_url=BASE_URL, api_key=api_key)


def case_non_stream(api_key):
    """Non-streaming chat completion via the orcarouter/auto router."""
    client = _client(api_key)
    resp = client.chat.completions.create(
        model="orcarouter/auto",
        messages=[{"role": "user", "content": "Reply with the single word: pong"}],
        max_tokens=256,
    )
    content = resp.choices[0].message.content
    return True, f"model={resp.model!r} content_len={len(content or '')}"


def case_stream(api_key):
    """Streaming chat completion via a namespaced upstream."""
    client = _client(api_key)
    stream = client.chat.completions.create(
        model="openai/gpt-4o-mini",
        messages=[{"role": "user", "content": "Count: 1 2 3"}],
        max_tokens=64,
        stream=True,
    )
    chunks = 0
    text = ""
    for chunk in stream:
        chunks += 1
        delta = chunk.choices[0].delta.content if chunk.choices else None
        if delta:
            text += delta
    return chunks > 0, f"chunks={chunks} text_len={len(text)}"


def case_tool_calling(api_key):
    """Tool calling via a namespaced upstream."""
    client = _client(api_key)
    tools = [
        {
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get the current weather for a city",
                "parameters": {
                    "type": "object",
                    "properties": {"city": {"type": "string"}},
                    "required": ["city"],
                },
            },
        }
    ]
    resp = client.chat.completions.create(
        model="openai/gpt-4o-mini",
        messages=[{"role": "user", "content": "What is the weather in Paris? Use the tool."}],
        tools=tools,
        tool_choice="auto",
        max_tokens=128,
    )
    tool_calls = resp.choices[0].message.tool_calls or []
    names = [tc.function.name for tc in tool_calls]
    return len(tool_calls) > 0, f"tool_calls={names}"


def case_bad_key(_api_key):
    """A bad API key must produce a 401 authentication error."""
    client = _client("sk-orca-invalid-key-000000000000")
    try:
        client.chat.completions.create(
            model="openai/gpt-4o-mini",
            messages=[{"role": "user", "content": "hi"}],
            max_tokens=16,
        )
        return False, "expected an auth error but the request succeeded"
    except AuthenticationError as e:
        return True, f"status={getattr(e, 'status_code', '401')} (AuthenticationError)"
    except APIStatusError as e:
        return e.status_code == 401, f"status={e.status_code}"


def case_wrong_model(api_key):
    """An unknown model must produce a 4xx error."""
    client = _client(api_key)
    try:
        client.chat.completions.create(
            model="orcarouter/this-model-does-not-exist",
            messages=[{"role": "user", "content": "hi"}],
            max_tokens=16,
        )
        return False, "expected an error but the request succeeded"
    except APIStatusError as e:
        return 400 <= e.status_code < 500, f"status={e.status_code}"


CASES = [
    ("non-stream chat (orcarouter/auto)", case_non_stream),
    ("stream chat (openai/gpt-4o-mini)", case_stream),
    ("tool calling (openai/gpt-4o-mini)", case_tool_calling),
    ("401 bad key", case_bad_key),
    ("wrong-model error", case_wrong_model),
]


def main():
    api_key = os.environ.get("ORCAROUTER_API_KEY")
    if not api_key:
        print("ORCAROUTER_API_KEY is not set. Get a key at https://www.orcarouter.ai/console", file=sys.stderr)
        sys.exit(2)

    print(f"OrcaRouter live test against {BASE_URL}\n")
    passed = 0
    for name, fn in CASES:
        try:
            ok, detail = fn(api_key)
        except Exception as exc:  # noqa: BLE001 - report any unexpected failure per case
            ok, detail = False, f"unexpected error: {type(exc).__name__}: {exc}"
        passed += 1 if ok else 0
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")

    print(f"\n{passed}/{len(CASES)} cases passed")
    sys.exit(0 if passed == len(CASES) else 1)


if __name__ == "__main__":
    main()
