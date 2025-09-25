"""Utilities for coordinating multi-pass MCP prompt evaluations."""

from __future__ import annotations

import logging
from contextvars import ContextVar
from typing import Any, Optional
from collections.abc import Callable


FollowUpBuilder = Callable[[dict[str, Any], str], Optional[str]]


def _insert_tool_message(
    *,
    messages: list[dict[str, Any]],
    tool_name: str,
    tool_content: str,
) -> list[dict[str, Any]]:
    """Insert a tool message immediately after the first assistant reply."""

    with_tool: list[dict[str, Any]] = []
    inserted = False
    for message in messages:
        with_tool.append(message)
        if message.get("role") == "assistant" and not inserted:
            logger.debug(
                "Inserting tool summary for %s after assistant message", tool_name
            )
            with_tool.append(
                {
                    "role": "assistant",
                    "content": (
                        f"Here is the result from tool `{tool_name}`:\n\n{tool_content}"
                    ),
                }
            )
            inserted = True

    if not inserted:
        logger.debug("No assistant message found; appending summary for %s", tool_name)
        with_tool.append(
            {
                "role": "assistant",
                "content": (f"Tool result from `{tool_name}`:\n\n{tool_content}"),
            }
        )

    return with_tool


def extract_user_query(dataset_item: dict[str, Any]) -> str | None:
    """Best-effort extraction of a user query from dataset item structures."""

    user_query = dataset_item.get("user_query")
    if user_query:
        return user_query

    payload = dataset_item.get("input")
    if isinstance(payload, dict):
        for key in ("query", "user_query", "prompt"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value

    return None


class MCPSecondPassCoordinator:
    """Tracks MCP tool summaries and builds second-pass message sets.

    TODO(opik-mcp): Replace this shim once optimizers understand multi-pass flows
    natively and expose tool transcripts without direct ChatPrompt mutation.
    """

    def __init__(
        self,
        *,
        tool_name: str,
        summary_var: ContextVar[str | None],
        follow_up_builder: FollowUpBuilder,
    ) -> None:
        self._tool_name = tool_name
        self._summary_var = summary_var
        self._follow_up_builder = follow_up_builder
        self._last_summary: str | None = None
        self._last_follow_up: str | None = None

    @property
    def tool_name(self) -> str:
        return self._tool_name

    def reset(self) -> None:
        self._summary_var.set(None)

    def record_summary(self, summary: str) -> None:
        logger.debug("Recording summary for %s", self.tool_name)
        self._summary_var.set(summary)

    def fetch_summary(self) -> str | None:
        return self._summary_var.get()

    def get_last_summary(self) -> str | None:
        return self._last_summary

    def build_second_pass_messages(
        self,
        *,
        base_messages: list[dict[str, Any]],
        dataset_item: dict[str, Any],
        summary_override: str | None = None,
    ) -> list[dict[str, Any]] | None:
        self._last_summary = None
        self._last_follow_up = None
        summary = (
            summary_override if summary_override is not None else self.fetch_summary()
        )
        if not summary:
            logger.debug(
                "No summary available for %s; skipping second pass", self.tool_name
            )
            return None

        logger.debug("Summary captured for %s", self.tool_name)
        logger.debug(
            "Summary preview for %s: %s",
            self.tool_name,
            summary[:160].replace(chr(10), " "),
        )

        messages = _insert_tool_message(
            messages=base_messages,
            tool_name=self.tool_name,
            tool_content=summary,
        )

        follow_up = self._follow_up_builder(dataset_item, summary)
        if follow_up:
            messages.append({"role": "user", "content": follow_up})
        logger.debug(
            "Follow-up appended for %s: %s",
            self.tool_name,
            follow_up[:120] if follow_up else "None",
        )

        self._last_summary = summary
        self._last_follow_up = follow_up
        self.reset()
        return messages


logger = logging.getLogger(__name__)
