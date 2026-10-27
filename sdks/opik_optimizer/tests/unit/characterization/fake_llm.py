"""Deterministic, content-aware fake LLM for the characterization tests.

Two boundaries are faked:

* ``opik_optimizer.core.llm_calls.call_model`` / ``call_model_async`` -- every
  optimizer-internal LLM call (candidate generation, mutation, reflection, root
  cause analysis, few-shot templating, tool description rewriting). The fake
  dispatches on the requested ``response_model`` (or, for free-text calls, on
  the request content) and returns a valid structured output.
* ``LiteLLMAgent._llm_complete`` -- the task/agent call. The fake renders an
  answer from the messages it receives, so the score of a prompt depends on its
  content (see :func:`agent_answer`).

Rewrites are content-derived: the fake finds the current prompt inside the
request (longest known system text that occurs in it), appends "hint"
sentences, and every hint makes the fake agent answer more items correctly. The
number of hints added is derived from a stable hash of the matched text and the
candidate index, never from call order, so results do not depend on thread
scheduling.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
import threading
from typing import Any

import litellm
from pydantic import BaseModel

HINTS: tuple[str, ...] = (
    "Answer with the exact value.",
    "Be concise.",
    "Think step by step.",
    "Double check the facts.",
    "Use the provided context.",
    "Respond in plain text.",
)
NEUTRAL_HINT = "Stay on task."

_ITEM_RE = re.compile(r"item (\d+)")
_EXAMPLE_ANSWER_RE = re.compile(r"\bA(\d+)\b")
_ADDRESS_RE = re.compile(r"0x[0-9a-fA-F]+")

SYNONYMS = {
    "answer": "reply",
    "question": "query",
    "helpful": "useful",
    "assistant": "helper",
    "exact": "precise",
    "concise": "brief",
    "value": "result",
    "facts": "details",
}


# ---------------------------------------------------------------------------
# Text helpers
# ---------------------------------------------------------------------------


def stable_int(*parts: Any) -> int:
    payload = "|".join(_ADDRESS_RE.sub("0x", str(p)) for p in parts)
    return int(hashlib.sha256(payload.encode("utf-8")).hexdigest()[:8], 16)


def content_text(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        texts = []
        for part in content:
            if isinstance(part, dict):
                if part.get("type") == "text":
                    texts.append(str(part.get("text", "")))
            elif isinstance(part, BaseModel):
                text = getattr(part, "text", None)
                if text is not None:
                    texts.append(str(text))
        return "\n".join(texts)
    return str(content)


def messages_text(messages: Any) -> str:
    if isinstance(messages, str):
        return messages
    out = []
    for message in messages or []:
        if isinstance(message, BaseModel):
            message = message.model_dump()
        if isinstance(message, dict):
            out.append(content_text(message.get("content")))
    return "\n".join(out)


def count_hints(text: str) -> int:
    return sum(1 for hint in HINTS if hint in text)


def add_hints(text: str, n: int) -> str:
    missing = [hint for hint in HINTS if hint not in text]
    additions = missing[:n] if n > 0 else []
    if not additions and NEUTRAL_HINT not in text:
        additions = [NEUTRAL_HINT]
    if not additions:
        return text
    return (text.rstrip() + " " + " ".join(additions)).strip()


def _first_text(content: Any) -> str:
    if isinstance(content, list):
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                return str(part.get("text", ""))
        return ""
    return str(content or "")


def _signature(messages: list[dict[str, Any]]) -> str:
    """Text used to recognise a prompt inside a meta-prompt request."""
    for message in messages:
        if message.get("role") == "system":
            return _first_text(message.get("content"))
    return _first_text(messages[0].get("content")) if messages else ""


def flatten_content_parts(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Mimic an LLM that answers with plain-string content (drops image parts)."""
    flattened = []
    for message in messages:
        content = message.get("content")
        if isinstance(content, list):
            texts = [
                str(p.get("text", ""))
                for p in content
                if isinstance(p, dict) and p.get("type") == "text"
            ]
            message = {**message, "content": " ".join(texts)}
        flattened.append(message)
    return flattened


def _rewrite_content(content: Any, n: int) -> Any:
    if isinstance(content, list):
        new_parts = copy.deepcopy(content)
        for part in new_parts:
            if isinstance(part, dict) and part.get("type") == "text":
                part["text"] = add_hints(str(part.get("text", "")), n)
                break
        return new_parts
    return add_hints(str(content or ""), n)


def rewrite_messages(messages: list[dict[str, Any]], n: int) -> list[dict[str, Any]]:
    """Append hints to the system message (or the first message) only."""
    new_messages = copy.deepcopy(messages)
    target = next(
        (m for m in new_messages if m.get("role") == "system"),
        new_messages[0] if new_messages else None,
    )
    if target is not None:
        target["content"] = _rewrite_content(target.get("content"), n)
    return new_messages


def _contains(haystack: str, needle: str) -> bool:
    if not needle:
        return False
    if needle in haystack:
        return True
    escaped = json.dumps(needle)[1:-1]
    return escaped in haystack


# ---------------------------------------------------------------------------
# Agent behaviour
# ---------------------------------------------------------------------------


def agent_answer(
    *,
    context_text: str,
    question_text: str,
    has_image: bool = False,
    tool_text: str = "",
    temperature: float | None = None,
) -> str:
    """Deterministic answer: more hints/examples/signals -> more items correct.

    Item ``i`` is answered correctly when ``i % 5 < 1 + k`` where ``k`` counts
    the hint sentences in the prompt (and tool descriptions), pairs of few-shot
    example answers, an attached image and a low temperature.
    """
    match = _ITEM_RE.search(question_text)
    if match is None:
        return "I do not know."
    idx = int(match.group(1))
    k = count_hints(context_text) + count_hints(tool_text)
    k += len(set(_EXAMPLE_ANSWER_RE.findall(context_text))) // 2
    if has_image:
        k += 1
    if temperature is not None and temperature <= 0.3:
        k += 1
    if idx % 5 < 1 + k:
        return f"A{idx}"
    return f"Not sure about item {idx}."


def _has_image(messages: list[dict[str, Any]]) -> bool:
    for message in messages:
        content = message.get("content")
        if isinstance(content, list):
            for part in content:
                if isinstance(part, dict) and part.get("type") == "image_url":
                    url = (part.get("image_url") or {}).get("url", "")
                    if url and "{" not in url:
                        return True
    return False


def _message_dict(message: Any) -> dict[str, Any]:
    if isinstance(message, dict):
        return message
    if hasattr(message, "to_dict"):
        return message.to_dict()
    if isinstance(message, BaseModel):
        return message.model_dump()
    return dict(message)


def make_model_response(contents: list[str]) -> litellm.ModelResponse:
    return litellm.ModelResponse(
        model="fake-model",
        choices=[
            {
                "index": i,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": text},
            }
            for i, text in enumerate(contents)
        ],
    )


def make_tool_call_response(name: str, arguments: dict[str, Any], call_id: str) -> Any:
    return litellm.ModelResponse(
        model="fake-model",
        choices=[
            {
                "index": 0,
                "finish_reason": "tool_calls",
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": call_id,
                            "type": "function",
                            "function": {
                                "name": name,
                                "arguments": json.dumps(arguments),
                            },
                        }
                    ],
                },
            }
        ],
    )


def fake_llm_complete(
    agent: Any,
    model: str | None,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]] | None,
    seed: int | None = None,
    model_kwargs: dict[str, Any] | None = None,
) -> Any:
    """Replacement for ``LiteLLMAgent._llm_complete`` (no network)."""
    _ = agent, model, seed
    msgs = [_message_dict(m) for m in messages]
    user_texts = [
        content_text(m.get("content")) for m in msgs if m.get("role") == "user"
    ]
    question_text = user_texts[-1] if user_texts else ""
    context_text = "\n".join(
        content_text(m.get("content"))
        for m in msgs
        if m.get("role") in ("system", "assistant")
        or (m.get("role") == "user" and content_text(m.get("content")) != question_text)
    )
    model_kwargs = model_kwargs or {}
    temperature = model_kwargs.get("temperature")
    tool_text = json.dumps(tools, sort_keys=True) if tools else ""

    if tools:
        tool_turns = [m for m in msgs if m.get("role") == "tool"]
        if not tool_turns:
            first_tool = tools[0].get("function", {}).get("name", "tool")
            match = _ITEM_RE.search(question_text)
            call_id = f"call_{match.group(1) if match else 'x'}"
            return make_tool_call_response(
                first_tool, {"query": question_text}, call_id
            )

    answer = agent_answer(
        context_text=context_text,
        question_text=question_text,
        has_image=_has_image(msgs),
        tool_text=tool_text,
        temperature=temperature if isinstance(temperature, (int, float)) else None,
    )
    n = model_kwargs.get("n", 1)
    n = n if isinstance(n, int) and n > 0 else 1
    return make_model_response([answer] * n)


# ---------------------------------------------------------------------------
# Optimizer-side LLM
# ---------------------------------------------------------------------------


def text_edits(
    original: list[dict[str, Any]], changed: list[dict[str, Any]]
) -> list[dict[str, str]]:
    edits = []
    for index, (before, after) in enumerate(zip(original, changed)):
        content, new = before["content"], after["content"]
        if isinstance(content, str) and content != new:
            edits.append({"segment_id": f"message:{index}", "text": new})
        elif isinstance(content, list) and isinstance(new, list):
            for part_index, (a, b) in enumerate(zip(content, new)):
                if a.get("type") == "text" and a.get("text") != b.get("text"):
                    edits.append(
                        {
                            "segment_id": f"message:{index}:part:{part_index}",
                            "text": b["text"],
                        }
                    )
    return edits


class FakeOptimizerLLM:
    """Replacement for ``call_model`` / ``call_model_async``."""

    def __init__(
        self,
        base_prompts: dict[str, list[dict[str, Any]]],
        base_tools: dict[str, str] | None = None,
        flatten_multimodal: bool = False,
    ) -> None:
        self._flatten = flatten_multimodal
        self._lock = threading.Lock()
        self._known: dict[str, list[list[dict[str, Any]]]] = {
            name: [copy.deepcopy(msgs)] for name, msgs in base_prompts.items()
        }
        self._base = {name: copy.deepcopy(m) for name, m in base_prompts.items()}
        self._tool_descriptions: dict[str, list[str]] = {
            name: [desc] for name, desc in (base_tools or {}).items()
        }
        self.calls: list[str] = []
        self.unknown_requests: list[str] = []

    # -- registry -----------------------------------------------------------

    def _register(self, name: str, messages: list[dict[str, Any]]) -> None:
        with self._lock:
            known = self._known.setdefault(name, [])
            if messages not in known:
                known.append(copy.deepcopy(messages))

    def _match(self, name: str, text: str) -> list[dict[str, Any]]:
        with self._lock:
            candidates = list(self._known.get(name, []))
        best: list[dict[str, Any]] | None = None
        best_len = -1
        for messages in candidates:
            sig = _signature(messages)
            if _contains(text, sig) and len(sig) > best_len:
                best, best_len = messages, len(sig)
        return copy.deepcopy(best if best is not None else self._base[name])

    def _match_any(self, text: str) -> tuple[str, list[dict[str, Any]]]:
        best_name = next(iter(self._base))
        best_len = -1
        with self._lock:
            items = [(n, list(ms)) for n, ms in self._known.items()]
        for name, candidates in items:
            for messages in candidates:
                sig = _signature(messages)
                if _contains(text, sig) and len(sig) > best_len:
                    best_name, best_len = name, len(sig)
        return best_name, self._match(best_name, text)

    def _names_in(self, text: str) -> list[str]:
        names = [name for name in self._base if _contains(text, name)]
        return names or list(self._base)

    def _candidate(
        self, name: str, current: list[dict[str, Any]], idx: int, kind: str
    ) -> list[dict[str, Any]]:
        # The first candidate of a request always adds >= 1 hint (so every
        # request can make progress); later ones may add 0 (neutral) to 2.
        h = stable_int(kind, _signature(current), idx)
        n = 1 + h % 2 if idx == 0 else h % 3
        new = rewrite_messages(current, n)
        self._register(name, new)
        return new

    def _tool_candidate(self, tool: str, text: str, idx: int) -> str:
        with self._lock:
            known = list(self._tool_descriptions.get(tool, []))
        current = max((d for d in known if _contains(text, d)), key=len, default=None)
        if current is None:
            current = known[0] if known else f"Tool {tool}."
        new = add_hints(current, 1 + stable_int("tool", current, idx) % 2)
        with self._lock:
            if new not in self._tool_descriptions.setdefault(tool, []):
                self._tool_descriptions[tool].append(new)
        return new

    # -- entry points ----------------------------------------------------------

    def call_model(self, **kwargs: Any) -> Any:
        from opik_optimizer.core import llm_calls

        llm_calls._increment_llm_counter_if_in_optimizer()
        messages = kwargs.get("messages") or []
        response_model = kwargs.get("response_model")
        text = messages_text(messages)
        if response_model is not None:
            result: Any = self._structured(response_model, text, messages)
        else:
            result = self._free_text(text)
        if kwargs.get("return_all"):
            return [result]
        return result

    async def call_model_async(self, **kwargs: Any) -> Any:
        return self.call_model(**kwargs)

    # -- structured ----------------------------------------------------------

    def _expected_count(self, text: str, default: int = 2) -> int:
        for pattern in (
            r"[Gg]enerate (?:exactly )?(\d+)",
            r"(\d+) (?:new |candidate |distinct |different )*prompts",
        ):
            match = re.search(pattern, text)
            if match:
                return max(1, min(int(match.group(1)), 4))
        return default

    def _structured(
        self, response_model: type[BaseModel], text: str, messages: list[Any]
    ) -> BaseModel:
        kind = response_model.__name__
        self.calls.append(kind)
        handler = getattr(self, f"_handle_{kind}", None)
        if handler is None:
            self.unknown_requests.append(kind)
            raise AssertionError(f"FakeOptimizerLLM: unhandled response_model {kind}")
        payload = handler(response_model, text, messages)
        return response_model.model_validate(payload)

    def _handle_PromptCandidatesResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        name, current = self._match_any(text)
        count = self._expected_count(text)
        return {
            "prompts": [
                {
                    "edits": text_edits(
                        current, self._candidate(name, current, i, "meta")
                    ),
                    "improvement_focus": f"focus {i}",
                    "reasoning": f"Added hints to candidate {i}.",
                }
                for i in range(count)
            ]
        }

    def _handle_AgentBundleCandidatesResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        count = self._expected_count(text)
        names = self._names_in(text)
        bundles = []
        for i in range(count):
            agents = []
            for name in names:
                current = self._match(name, text)
                agents.append(
                    {
                        "name": name,
                        "edits": text_edits(
                            current, self._candidate(name, current, i, "bundle")
                        ),
                        "improvement_focus": f"focus {i}",
                        "reasoning": f"Bundle {i} update for {name}.",
                    }
                )
            bundles.append({"agents": agents, "bundle_improvement_focus": f"b{i}"})
        return {"candidates": bundles}

    def _handle_PatternExtractionResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        return {
            "patterns": [
                {"pattern": "State the exact value", "example": HINTS[0]},
                "Keep answers concise",
            ]
        }

    def _handle_StyleInferenceResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        return {"style": "Short answers that state the exact code."}

    def _handle_MutationResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        name, current = self._match_any(text)
        return {
            "edits": text_edits(current, self._candidate(name, current, 0, "mutation"))
        }

    def _handle_CrossoverResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        name, current = self._match_any(text)
        return {
            "child_1": text_edits(
                current, self._candidate(name, current, 1, "crossover")
            ),
            "child_2": text_edits(
                current, self._candidate(name, current, 2, "crossover")
            ),
        }

    def _handle_PopulationResponse(
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        name, current = self._match_any(text)
        kind = "fresh" if "from scratch" in text.lower() else "variation"
        self.calls[-1] = "fresh_start" if kind == "fresh" else "variation"
        match = re.search(r"Generate (\d+) diverse", text)
        count = int(match.group(1)) if match else 1
        return {
            "prompts": [
                {
                    "edits": text_edits(
                        current,
                        self._candidate(
                            name,
                            current,
                            (10 + index) if kind == "fresh" else index,
                            kind,
                        ),
                    )
                }
                for index in range(count)
            ]
        }

    def _handle_RootCauseAnalysis(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        return {
            "failure_modes": [
                {
                    "name": "missing_exact_value",
                    "description": "The answer does not state the exact code.",
                    "root_cause": "The prompt never asks for the exact value.",
                }
            ]
        }

    def _handle_HierarchicalRootCauseAnalysis(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        return {
            "total_test_cases": 6,
            "num_batches": 1,
            "unified_failure_modes": [
                {
                    "name": "missing_exact_value",
                    "description": "The answer does not state the exact code.",
                    "root_cause": "The prompt never asks for the exact value.",
                }
            ],
            "synthesis_notes": "Single failure mode across batches.",
        }

    def _handle_ImprovedPromptsResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        for i, field in enumerate(model.model_fields):
            name = field if field in self._base else self._match_any(text)[0]
            current = self._match(name, text)
            entry: dict[str, Any] = {
                "reasoning": f"Improve {field}.",
                "edits": text_edits(current, self._candidate(name, current, i, "hrpo")),
            }
            if self._tool_descriptions:
                entry["tool_descriptions"] = [
                    {"name": tool, "description": self._tool_candidate(tool, text, i)}
                    for tool in sorted(self._tool_descriptions)
                    if _contains(text, tool)
                ] or None
            payload[field] = entry
        return payload

    def _handle_FewShotPromptMessages(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        from opik_optimizer.algorithms.few_shot_bayesian_optimizer import (
            prompts as few_shot_prompts,
        )

        placeholder = few_shot_prompts.FEW_SHOT_EXAMPLE_PLACEHOLDER
        user_payload: dict[str, Any] = {}
        for message in messages:
            if isinstance(message, dict) and message.get("role") == "user":
                try:
                    user_payload = json.loads(
                        str(message.get("content", "")).split("\n\nReturn ONLY")[0]
                    )
                except json.JSONDecodeError:
                    continue
        prompt_entries = user_payload.get("prompts", [])
        keys = [key for key in user_payload.get("example_fields", []) if key != "id"]
        in_key = "question" if "question" in keys else (keys[0] if keys else "input")
        out_key = "answer" if "answer" in keys else (keys[-1] if keys else "output")
        payload: dict[str, Any] = {"template": f"Q: {{{in_key}}}\nA: {{{out_key}}}"}
        fields = [field for field in model.model_fields if field != "template"]
        for field, entry in zip(fields, prompt_entries):
            segment = entry["segments"][0]
            payload[field] = [
                {
                    "segment_id": segment["segment_id"],
                    "text": segment["text"] + f"\n\nExamples:\n{placeholder}",
                }
            ]
        return payload

    def _handle_ToolDescriptionCandidatesResponse(  # noqa: N802
        self, model: type[BaseModel], text: str, messages: list[Any]
    ) -> dict[str, Any]:
        count = self._expected_count(text)
        tools = [t for t in sorted(self._tool_descriptions) if _contains(text, t)]
        tools = tools or sorted(self._tool_descriptions)
        return {
            "prompts": [
                {
                    "tool_descriptions": [
                        {
                            "name": tool,
                            "description": self._tool_candidate(tool, text, i),
                        }
                        for tool in tools
                    ],
                    "parameter_descriptions": None,
                    "improvement_focus": f"tool focus {i}",
                    "reasoning": f"Tool candidate {i}.",
                }
                for i in range(count)
            ]
        }

    # -- free text -------------------------------------------------------------

    def _free_text(self, text: str) -> str:
        if "I provided an assistant with the following instructions" in text:
            self.calls.append("gepa_reflection")
            match = re.search(r"```\n(.*?)\n```", text, re.DOTALL)
            current = match.group(1) if match else ""
            new = add_hints(current, 1 + stable_int("gepa", current) % 2)
            return f"```\n{new}\n```"

        match = re.search(r"single synonym for the word '([^']*)'", text)
        if match:
            self.calls.append("synonym")
            word = match.group(1)
            return SYNONYMS.get(word.lower(), word)

        match = re.search(
            r"Modify this phrase while keeping the same meaning: '(.*)'\. Return only",
            text,
            re.DOTALL,
        )
        if match:
            self.calls.append("rephrase")
            return match.group(1)

        if "generate novel, effective prompts from scratch" in text:
            self.calls.append("fresh_start")
            num = re.search(r"generate (\d+) diverse", text)
            count = int(num.group(1)) if num else 1
            name, current = self._match_any(text)
            return json.dumps(
                [self._candidate(name, current, 10 + i, "fresh") for i in range(count)]
            )

        if "generate a diverse set of alternative prompts" in text:
            self.calls.append("variation")
            num = re.search(r"Generate (\d+) diverse alternative", text)
            count = int(num.group(1)) if num else 1
            name, current = self._match_any(text)
            return json.dumps(
                {
                    "prompts": [
                        {
                            "prompt": self._candidate(
                                name, current, 20 + i, "variation"
                            ),
                            "strategy": f"variation {i}",
                        }
                        for i in range(count)
                    ]
                }
            )

        self.calls.append("unknown_free_text")
        self.unknown_requests.append(text[:200])
        return "OK"
