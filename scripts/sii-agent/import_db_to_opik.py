"""
OpenCode SQLite session -> Opik trace uploader (v2, standalone).

Self-contained: all parsing/DB logic is inlined (no opencode_db_to_langsmith dep).

Key features:
- All timestamps come from the DB (start_time / end_time on every span)
- Recursively loads child sessions for full nested trace
- Uses Opik's low-level Python SDK for trace/span creation
- Captures all metrics: token usage, cache stats, tool estimates, errors, reasoning
- Sequential timing: LLM end = first tool start (not step.time_completed),
  so LLM and tool spans are sequential, not overlapping
- Per-step token accounting with `token_accounting: "per-step"` marker

Usage:
  pip install opik
  python3 import_db_to_opik_v2.py --db /path/to/opencode.db
  python3 import_db_to_opik_v2.py --db /path/to/opencode.db --session-id <id>
  python3 import_db_to_opik_v2.py --db /path/to/opencode.db --dry-run
  python3 import_db_to_opik_v2.py --db /path/to/opencode.db --project my-project
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from opik import id_helpers


# ── Data structures ──────────────────────────────────────────────


@dataclass
class ToolCall:
    call_id: str
    name: str
    start_time: str
    end_time: str
    input: Any = field(default_factory=dict)
    output: Any = None
    status: str = "unknown"
    title: str = ""
    tool_metadata: dict[str, Any] = field(default_factory=dict)
    error: str | None = None
    # sub-agent fields (tool == "task")
    is_subagent: bool = False
    child_session_id: str = ""
    child_agent: str = ""
    child_model: str = ""
    child_provider: str = ""
    child_description: str = ""
    child_prompt: str = ""


@dataclass
class AgentStep:
    step_index: int
    message_id: str
    model: str
    provider_id: str
    agent: str
    mode: str
    variant: str
    timestamp: str
    time_completed: str
    path_cwd: str = ""
    path_root: str = ""
    text: str = ""
    reasoning: str = ""
    tools: list[ToolCall] = field(default_factory=list)
    usage: dict[str, int] = field(default_factory=dict)
    finish_reason: str | None = None
    cost: float | int | None = None
    incomplete: bool = False


@dataclass
class Turn:
    turn_index: int
    user_input: str
    timestamp: str
    is_system: bool = False
    models: set[str] = field(default_factory=set)
    agents: set[str] = field(default_factory=set)
    steps: list[AgentStep] = field(default_factory=list)
    final_output: str = ""
    usage: dict[str, int] = field(default_factory=lambda: {
        "input_tokens": 0, "output_tokens": 0,
        "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0,
        "reasoning_tokens": 0,
    })


@dataclass
class SessionMeta:
    session_id: str
    title: str
    directory: str
    time_created: int
    time_updated: int
    version: str = ""


@dataclass
class ReasoningRound:
    round_idx: int
    step_items: list[tuple[int, AgentStep]] = field(default_factory=list)


# ── Helpers ──────────────────────────────────────────────────────


def ms_to_iso(ms: int | None) -> str:
    if not ms:
        return datetime.now(timezone.utc).isoformat()
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat()


TIMESTAMP_SHIFT: timedelta | None = None


def _parse_ts_raw(value: str) -> datetime:
    if value:
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            pass
    return datetime.now(timezone.utc)


def configure_timestamp_shift(mode: str, anchor_ts: str) -> None:
    global TIMESTAMP_SHIFT
    if mode == "shift-to-now":
        TIMESTAMP_SHIFT = datetime.now(timezone.utc) - _parse_ts_raw(anchor_ts)
    else:
        TIMESTAMP_SHIFT = None


def parse_ts(value: str) -> datetime:
    dt = _parse_ts_raw(value)
    if TIMESTAMP_SHIFT is not None:
        return dt + TIMESTAMP_SHIFT
    return dt


def strip_model_date(model: str) -> str:
    return re.sub(r"-\d{8}$", "", model) if model else model


def stringify_content(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            if isinstance(item, dict):
                if item.get("type") == "text" and item.get("text"):
                    parts.append(str(item["text"]))
                else:
                    parts.append(json.dumps(item, ensure_ascii=False))
            else:
                parts.append(str(item))
        return "\n".join(part for part in parts if part)
    if isinstance(value, dict):
        return json.dumps(value, ensure_ascii=False, indent=2)
    return str(value)


def build_usage_metadata(usage: dict[str, Any]) -> dict[str, Any] | None:
    if not usage:
        return None
    input_tokens = int(usage.get("input_tokens", 0) or 0)
    output_tokens = int(usage.get("output_tokens", 0) or 0)
    reasoning_tokens = int(usage.get("reasoning_tokens", 0) or 0)
    cache_read = int(usage.get("cache_read_input_tokens", 0) or 0)
    cache_creation = int(usage.get("cache_creation_input_tokens", 0) or 0)
    total_tokens = input_tokens + output_tokens + reasoning_tokens + cache_read + cache_creation
    if total_tokens == 0:
        return None
    combined_input = input_tokens + cache_read + cache_creation
    total_tokens = combined_input + output_tokens + reasoning_tokens
    return {
        "billed_input_tokens": input_tokens,
        "input_tokens": combined_input,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
        "output_token_details": {"reasoning": reasoning_tokens},
        "input_token_details": {
            "cache_read": cache_read,
            "cache_creation": cache_creation,
        },
    }


def tokens_from_msg(msg_data: dict[str, Any]) -> dict[str, int]:
    t = msg_data.get("tokens") or {}
    cache = t.get("cache") or {}
    return {
        "input_tokens": int(t.get("input", 0) or 0),
        "output_tokens": int(t.get("output", 0) or 0),
        "reasoning_tokens": int(t.get("reasoning", 0) or 0),
        "cache_read_input_tokens": int(cache.get("read", 0) or 0),
        "cache_creation_input_tokens": int(cache.get("write", 0) or 0),
    }


def estimate_tokens(content: Any) -> int:
    if content is None:
        return 0
    text = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
    return max(1, len(text) // 4) if text else 0


def step_end_timestamp(step: AgentStep) -> str:
    if step.time_completed:
        return step.time_completed
    if step.tools:
        return max(t.end_time or t.start_time or step.timestamp for t in step.tools)
    return step.timestamp


def accumulate_usage(target: dict[str, int], source: dict[str, int]) -> None:
    for k in ("input_tokens", "output_tokens", "cache_read_input_tokens",
              "cache_creation_input_tokens", "reasoning_tokens"):
        target[k] = target.get(k, 0) + source.get(k, 0)


# ── Database ─────────────────────────────────────────────────────


def open_db(db_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{db_path}?immutable=1", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def resolve_session(conn: sqlite3.Connection, session_id: str | None) -> SessionMeta:
    if session_id:
        row = conn.execute(
            "SELECT id, title, directory, time_created, time_updated, version FROM session WHERE id = ?",
            (session_id,),
        ).fetchone()
        if row is None:
            raise SystemExit(f"Session not found: {session_id}")
    else:
        row = conn.execute(
            "SELECT id, title, directory, time_created, time_updated, version "
            "FROM session ORDER BY time_created DESC LIMIT 1"
        ).fetchone()
        if row is None:
            raise SystemExit("No sessions found in database")
    return SessionMeta(
        session_id=row["id"],
        title=row["title"],
        directory=row["directory"],
        time_created=row["time_created"],
        time_updated=row["time_updated"],
        version=row["version"] or "",
    )


def session_exists(conn: sqlite3.Connection, session_id: str) -> bool:
    row = conn.execute("SELECT 1 FROM session WHERE id = ?", (session_id,)).fetchone()
    return row is not None


def load_messages(conn: sqlite3.Connection, session_id: str) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT id, data FROM message WHERE session_id = ? ORDER BY time_created, id",
        (session_id,),
    ).fetchall()
    return [{"id": row["id"], "data": json.loads(row["data"])} for row in rows]


def load_parts(conn: sqlite3.Connection, message_id: str) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT data FROM part WHERE message_id = ? ORDER BY time_created, id",
        (message_id,),
    ).fetchall()
    return [json.loads(row["data"]) for row in rows]


# ── Parsing ───────────────────────────────────────────────────────


def parse_turns(conn: sqlite3.Connection, session_id: str | None) -> tuple[list[Turn], SessionMeta]:
    session_meta = resolve_session(conn, session_id)
    turns: list[Turn] = []
    current_turn: Turn | None = None

    for msg in load_messages(conn, session_meta.session_id):
        msg_data = msg["data"]
        role = msg_data.get("role")
        time_info = msg_data.get("time") or {}
        timestamp = ms_to_iso(time_info.get("created"))
        time_completed = ms_to_iso(time_info.get("completed")) if time_info.get("completed") else timestamp

        if role == "user":
            user_text_parts: list[str] = []
            for part in load_parts(conn, msg["id"]):
                if part.get("type") == "text" and part.get("text"):
                    user_text_parts.append(str(part["text"]))
            user_text = "\n".join(user_text_parts).strip()
            if not user_text:
                continue
            is_system = (
                user_text.startswith("<system-reminder>")
                or user_text.startswith("[BACKGROUND TASK")
                or user_text.startswith("[ALL BACKGROUND TASKS")
            )
            current_turn = Turn(
                turn_index=len(turns) + 1,
                user_input=user_text,
                timestamp=timestamp,
                is_system=is_system,
            )
            turns.append(current_turn)
            continue

        if role != "assistant" or current_turn is None:
            continue

        model = msg_data.get("modelID") or "unknown-model"
        provider_id = msg_data.get("providerID") or ""
        agent_name = msg_data.get("agent") or ""
        mode = msg_data.get("mode") or ""
        variant = msg_data.get("variant") or ""
        path_info = msg_data.get("path") or {}
        msg_cost = msg_data.get("cost")
        msg_finish = msg_data.get("finish") or None
        msg_tokens = tokens_from_msg(msg_data)

        current_turn.models.add(model)
        if agent_name:
            current_turn.agents.add(agent_name)

        step = AgentStep(
            step_index=len(current_turn.steps) + 1,
            message_id=msg["id"],
            model=model,
            provider_id=provider_id,
            agent=agent_name,
            mode=mode,
            variant=variant,
            timestamp=timestamp,
            time_completed=time_completed,
            path_cwd=path_info.get("cwd") or "",
            path_root=path_info.get("root") or "",
            cost=msg_cost,
            finish_reason=msg_finish,
            usage=msg_tokens,
        )

        for part in load_parts(conn, msg["id"]):
            ptype = part.get("type")

            if ptype == "reasoning":
                reasoning = (part.get("text") or "").strip()
                if reasoning:
                    step.reasoning = (
                        f"{step.reasoning}\n\n{reasoning}".strip()
                        if step.reasoning else reasoning
                    )

            elif ptype == "text":
                text = (part.get("text") or "").strip()
                if text:
                    step.text = f"{step.text}\n\n{text}".strip() if step.text else text

            elif ptype == "tool":
                state = part.get("state") or {}
                state_time = state.get("time") or {}
                tool_start = ms_to_iso(state_time.get("start")) if state_time.get("start") else timestamp
                tool_end = ms_to_iso(state_time.get("end")) if state_time.get("end") else tool_start

                output = state.get("output")
                error = None
                status = state.get("status") or "unknown"
                if status not in {"completed", "ok", "success"}:
                    error = stringify_content(output) or f"tool status={status}"

                tool_name = part.get("tool", "unknown")
                call_meta = state.get("metadata") or {}
                tool_input = state.get("input") or {}

                is_subagent = tool_name == "task"
                child_session_id = ""
                child_agent = ""
                child_model = ""
                child_provider = ""
                child_description = ""
                child_prompt = ""
                if is_subagent:
                    child_session_id = call_meta.get("sessionId") or ""
                    child_agent = call_meta.get("agent") or ""
                    child_model_info = call_meta.get("model") or {}
                    child_model = child_model_info.get("modelID") or ""
                    child_provider = child_model_info.get("providerID") or ""
                    child_description = call_meta.get("description") or tool_input.get("description") or ""
                    child_prompt = call_meta.get("prompt") or tool_input.get("prompt") or ""

                step.tools.append(ToolCall(
                    call_id=part.get("callID") or str(uuid.uuid4()),
                    name=tool_name,
                    start_time=tool_start,
                    end_time=tool_end,
                    input=tool_input,
                    output=output,
                    status=status,
                    title=part.get("title") or "",
                    tool_metadata=call_meta,
                    error=error,
                    is_subagent=is_subagent,
                    child_session_id=child_session_id,
                    child_agent=child_agent,
                    child_model=child_model,
                    child_provider=child_provider,
                    child_description=child_description,
                    child_prompt=child_prompt,
                ))

            elif ptype == "step-finish":
                tokens = part.get("tokens") or {}
                cache = tokens.get("cache") or {}
                step.usage = {
                    "input_tokens": int(tokens.get("input", 0) or 0),
                    "output_tokens": int(tokens.get("output", 0) or 0),
                    "reasoning_tokens": int(tokens.get("reasoning", 0) or 0),
                    "cache_read_input_tokens": int(cache.get("read", 0) or 0),
                    "cache_creation_input_tokens": int(cache.get("write", 0) or 0),
                }
                if part.get("reason"):
                    step.finish_reason = part["reason"]
                if part.get("cost") is not None:
                    step.cost = part["cost"]

        if not any([step.text, step.reasoning, step.tools, step.usage]):
            continue

        if not step.usage:
            step.incomplete = True
        if step.text:
            current_turn.final_output = step.text

        accumulate_usage(current_turn.usage, step.usage)
        current_turn.steps.append(step)

    for t in turns:
        if not t.steps and not t.is_system:
            t.is_system = True

    return turns, session_meta


# ── Preview / Token Summary ──────────────────────────────────────


def _fmt_k(n: int) -> str:
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}k"
    return str(n)


def _compute_token_summary(turns: list[Turn]) -> dict[str, int]:
    all_steps = [s for t in turns for s in t.steps]
    t_in = sum(s.usage.get("input_tokens", 0) for s in all_steps)
    t_out = sum(s.usage.get("output_tokens", 0) for s in all_steps)
    t_cr = sum(s.usage.get("cache_read_input_tokens", 0) for s in all_steps)
    t_cw = sum(s.usage.get("cache_creation_input_tokens", 0) for s in all_steps)
    t_reason = sum(s.usage.get("reasoning_tokens", 0) for s in all_steps)
    last = all_steps[-1].usage if all_steps else {}
    e_in = last.get("input_tokens", 0)
    e_out = last.get("output_tokens", 0)
    e_cr = last.get("cache_read_input_tokens", 0)
    e_cw = last.get("cache_creation_input_tokens", 0)
    e_reason = last.get("reasoning_tokens", 0)
    return {
        "total_input": t_in, "total_output": t_out,
        "total_cache_read": t_cr, "total_cache_write": t_cw,
        "total_reasoning": t_reason,
        "snap_input": e_in, "snap_output": e_out,
        "snap_cache_read": e_cr, "snap_cache_write": e_cw,
        "snap_reasoning": e_reason,
    }


def _print_token_summary(stats: dict[str, int], indent: str = "") -> None:
    reason_total = stats.get("total_reasoning", 0)
    reason_snap = stats.get("snap_reasoning", 0)
    reason_t = f"  reasoning={_fmt_k(reason_total)}" if reason_total else ""
    reason_e = f"  reasoning={_fmt_k(reason_snap)}" if reason_snap else ""
    print(f"{indent}total:     input={_fmt_k(stats['total_input'])}  "
          f"cache_read={_fmt_k(stats['total_cache_read'])}  "
          f"cache_write={_fmt_k(stats['total_cache_write'])}  "
          f"output={_fmt_k(stats['total_output'])}{reason_t}")
    print(f"{indent}effective: input={_fmt_k(stats['snap_input'])}  "
          f"cache_read={_fmt_k(stats['snap_cache_read'])}  "
          f"cache_write={_fmt_k(stats['snap_cache_write'])}  "
          f"output={_fmt_k(stats['snap_output'])}{reason_e}")


def preview_turns(turns: list[Turn], conn: sqlite3.Connection, indent: str = "") -> None:
    for turn in turns:
        sys_tag = " [SYSTEM]" if turn.is_system else ""
        print(f"\n{indent}Turn {turn.turn_index}{sys_tag}  [{turn.timestamp}]")
        print(f"{indent}  user: {turn.user_input[:160]}")
        print(f"{indent}  models: {', '.join(sorted(turn.models))}")
        if turn.agents:
            print(f"{indent}  agents: {', '.join(sorted(turn.agents))}")
        u = turn.usage
        print(f"{indent}  tokens: in={u['input_tokens']} out={u['output_tokens']} "
              f"cr={u['cache_read_input_tokens']} cc={u['cache_creation_input_tokens']} "
              f"reason={u['reasoning_tokens']}")
        for round_item in _group_reasoning_rounds(turn):
            round_meta = _round_metadata(round_item, turn, "")
            opener = round_meta.get("opener_text") or "(tool-only)"
            print(f"{indent}  Round {round_item.round_idx}: {opener[:80]}")
            for llm_idx, step in round_item.step_items:
                model_display = strip_model_date(step.model) if step.model else ""
                su = step.usage
                model_tag = f" ({model_display})" if model_display else ""
                tools_tag = f" -> {len(step.tools)} tools" if step.tools else ""
                print(f"{indent}    [{llm_idx}] LLM{model_tag}{tools_tag}"
                      f"  [ctx={_fmt_k(su.get('input_tokens',0))} out={_fmt_k(su.get('output_tokens',0))}]")
                if step.text:
                    print(f"{indent}      text: {step.text[:160]}")
                if step.reasoning:
                    print(f"{indent}      reasoning: {step.reasoning[:120]}")
                for t in step.tools:
                    input_est = estimate_tokens(
                        t.name + json.dumps(t.input, ensure_ascii=False) if t.input else t.name
                    )
                    output_est = estimate_tokens(stringify_content(t.output))
                    err_tag = " [ERR]" if t.error else ""
                    if t.is_subagent:
                        print(f"{indent}      -> {t.name}[{t.child_agent or t.child_model}]{err_tag}"
                              f"  ~{_fmt_k(input_est)}in/{_fmt_k(output_est)}out")
                        if t.child_session_id and session_exists(conn, t.child_session_id):
                            child_turns, child_meta = parse_turns(conn, t.child_session_id)
                            if child_turns:
                                preview_turns(child_turns, conn, indent=indent + "        ")
                            else:
                                print(f"{indent}        (empty session)")
                    else:
                        print(f"{indent}      -> {t.name}{err_tag}"
                              f"  ~{_fmt_k(input_est)}in/{_fmt_k(output_est)}out")


def preview(turns: list[Turn], session_meta: SessionMeta, conn: sqlite3.Connection) -> None:
    all_steps = [s for t in turns for s in t.steps]
    all_models = sorted({s.model for s in all_steps})
    total_tools = sum(len(s.tools) for s in all_steps)
    print(f"session={session_meta.session_id}  [{', '.join(all_models)}]")
    print(f"title={session_meta.title}")
    print(f"turns={len(turns)}  llm_calls={len(all_steps)}  tool_calls={total_tools}")
    stats = _compute_token_summary(turns)
    _print_token_summary(stats)
    preview_turns(turns, conn)


# ── Helpers ──────────────────────────────────────────────────────


def _make_error_info(error_text: str | None) -> dict[str, str] | None:
    """Build an Opik ErrorInfoDict from an error string, or None."""
    if not error_text:
        return None
    return {
        "exception_type": "ToolError",
        "message": error_text[:500],
        "traceback": error_text,
    }


def _opik_usage(usage_meta: dict[str, Any] | None) -> dict[str, Any] | None:
    """Convert our usage_metadata dict to Opik-compatible usage with OpenAI keys.

    Opik requires prompt_tokens / completion_tokens / total_tokens at the top
    level for the UI to render token counts.
    """
    if not usage_meta:
        return None
    return {
        # OpenAI-format keys for Opik UI
        "prompt_tokens": usage_meta.get("billed_input_tokens", usage_meta.get("input_tokens", 0)),
        "completion_tokens": usage_meta.get("output_tokens", 0),
        "total_tokens": usage_meta.get("total_tokens", 0),
        # Keep original detail
        **usage_meta,
    }


def _opik_usage_ints(usage_meta: dict[str, Any] | None) -> dict[str, int] | None:
    """Convert usage metadata to the integer-only payload expected by REST spans."""
    if not usage_meta:
        return None

    details = usage_meta.get("input_token_details") or {}
    result = {
        "prompt_tokens": int(usage_meta.get("billed_input_tokens", usage_meta.get("input_tokens", 0)) or 0),
        "completion_tokens": int(usage_meta.get("output_tokens", 0) or 0),
        "total_tokens": int(usage_meta.get("total_tokens", 0) or 0),
        "cache_read": int(details.get("cache_read", 0) or 0),
        "cache_creation": int(details.get("cache_creation", 0) or 0),
    }
    return {k: v for k, v in result.items() if v}


def _compute_turn_end(turn: Turn) -> datetime:
    """Return the effective end time for a turn."""
    turn_end_ts = step_end_timestamp(turn.steps[-1]) if turn.steps else turn.timestamp
    turn_start = parse_ts(turn.timestamp)
    turn_end = parse_ts(turn_end_ts)
    if turn_end < turn_start:
        return turn_start
    return turn_end


def _step_llm_end_time(step: AgentStep) -> datetime:
    llm_start = parse_ts(step.timestamp)
    if step.tools:
        llm_end = min(parse_ts(tool.start_time) for tool in step.tools)
    else:
        llm_end = parse_ts(step.time_completed)
    if llm_end < llm_start:
        return llm_start
    return llm_end


def _group_reasoning_rounds(turn: Turn) -> list[ReasoningRound]:
    rounds: list[ReasoningRound] = []
    current: ReasoningRound | None = None
    for llm_idx, step in enumerate(turn.steps, start=1):
        starts_new_round = bool((step.text or "").strip())
        if current is None or starts_new_round:
            current = ReasoningRound(round_idx=len(rounds) + 1)
            rounds.append(current)
        current.step_items.append((llm_idx, step))
    return rounds


def _round_end_time(round_item: ReasoningRound) -> datetime:
    last_tool_end = ""
    for _, step in round_item.step_items:
        for tool in step.tools:
            tool_ts = tool.end_time or tool.start_time
            if tool_ts and tool_ts > last_tool_end:
                last_tool_end = tool_ts
    if last_tool_end:
        return parse_ts(last_tool_end)
    return _step_llm_end_time(round_item.step_items[-1][1])


def _round_metadata(round_item: ReasoningRound, turn: Turn, session_id: str) -> dict[str, Any]:
    steps = [step for _, step in round_item.step_items]
    api_input = sum(int(step.usage.get("input_tokens", 0) or 0) for step in steps)
    api_output = sum(int(step.usage.get("output_tokens", 0) or 0) for step in steps)
    api_cache_read = sum(int(step.usage.get("cache_read_input_tokens", 0) or 0) for step in steps)
    api_cache_creation = sum(int(step.usage.get("cache_creation_input_tokens", 0) or 0) for step in steps)
    api_reasoning = sum(int(step.usage.get("reasoning_tokens", 0) or 0) for step in steps)
    last_usage = steps[-1].usage if steps else {}
    snapshot_input = int(last_usage.get("input_tokens", 0) or 0)
    snapshot_cache_read = int(last_usage.get("cache_read_input_tokens", 0) or 0)
    snapshot_cache_creation = int(last_usage.get("cache_creation_input_tokens", 0) or 0)
    snapshot_output = int(last_usage.get("output_tokens", 0) or 0)
    snapshot_reasoning = int(last_usage.get("reasoning_tokens", 0) or 0)
    round_start = parse_ts(steps[0].timestamp)
    round_end = _round_end_time(round_item)
    duration_ms = max(0, int((round_end - round_start).total_seconds() * 1000))
    opener = next((step.text.strip() for step in steps if (step.text or "").strip()), "")
    return {
        "thread_id": session_id,
        "turn_idx": turn.turn_index,
        "round_idx": round_item.round_idx,
        "duration_ms": duration_ms,
        "llm_calls": len(steps),
        "tool_calls": sum(len(step.tools) for step in steps),
        "subagent_calls": sum(1 for step in steps for tool in step.tools if tool.is_subagent),
        "api_billed_input_tokens": api_input,
        "api_billed_output_tokens": api_output,
        "api_billed_cache_read_tokens": api_cache_read,
        "api_billed_cache_creation_tokens": api_cache_creation,
        "api_billed_reasoning_tokens": api_reasoning,
        "incremental_input_tokens": api_input,
        "incremental_output_tokens": api_output,
        "incremental_cache_read_tokens": api_cache_read,
        "incremental_cache_creation_tokens": api_cache_creation,
        "incremental_reasoning_tokens": api_reasoning,
        "snapshot_input_tokens": snapshot_input,
        "snapshot_cache_read_tokens": snapshot_cache_read,
        "snapshot_cache_creation_tokens": snapshot_cache_creation,
        "snapshot_output_tokens": snapshot_output,
        "snapshot_reasoning_tokens": snapshot_reasoning,
        "snapshot_context_tokens": snapshot_input + snapshot_cache_read + snapshot_cache_creation,
        "snapshot_total_tokens": (
            snapshot_input + snapshot_cache_read + snapshot_cache_creation + snapshot_output
        ),
        "has_visible_text": bool(opener),
        "starts_with_tool_only": bool(steps and not (steps[0].text or "").strip()),
        "opener_text": opener[:240] if opener else "",
        "models": sorted({strip_model_date(step.model) for step in steps if step.model}),
        "step_indices": [step.step_index for step in steps],
        "agents": sorted({step.agent for step in steps if step.agent}),
    }


def _turn_metadata(turn: Turn, session_id: str, depth: int) -> dict[str, Any]:
    """Build turn metadata using OpenCode's per-step token semantics."""
    turn_start = parse_ts(turn.timestamp)
    turn_end = _compute_turn_end(turn)
    token_stats = _compute_token_summary([turn])
    duration_ms = max(0, int((turn_end - turn_start).total_seconds() * 1000))
    snapshot_context = (
        token_stats["snap_input"]
        + token_stats["snap_cache_read"]
        + token_stats["snap_cache_write"]
    )

    return {
        "thread_id": session_id,
        "session_id": session_id,
        "turn_idx": turn.turn_index,
        "turn_index": turn.turn_index,
        "is_system": turn.is_system,
        "depth": depth,
        "duration_ms": duration_ms,
        "models": sorted(turn.models),
        "agents": sorted(turn.agents),
        "step_count": len(turn.steps),
        "llm_calls": len(turn.steps),
        "tool_calls": sum(len(step.tools) for step in turn.steps),
        "subagent_calls": sum(1 for step in turn.steps for tool in step.tools if tool.is_subagent),
        "tool_success": sum(1 for step in turn.steps for tool in step.tools if not tool.error),
        "tool_error": sum(1 for step in turn.steps for tool in step.tools if tool.error),
        "token_accounting": "per-step",
        "api_billed_input_tokens": token_stats["total_input"],
        "api_billed_output_tokens": token_stats["total_output"],
        "api_billed_cache_read_tokens": token_stats["total_cache_read"],
        "api_billed_cache_creation_tokens": token_stats["total_cache_write"],
        "api_billed_reasoning_tokens": token_stats["total_reasoning"],
        "incremental_input_tokens": token_stats["total_input"],
        "incremental_output_tokens": token_stats["total_output"],
        "incremental_cache_read_tokens": token_stats["total_cache_read"],
        "incremental_cache_creation_tokens": token_stats["total_cache_write"],
        "incremental_reasoning_tokens": token_stats["total_reasoning"],
        "snapshot_input_tokens": token_stats["snap_input"],
        "snapshot_output_tokens": token_stats["snap_output"],
        "snapshot_cache_read_tokens": token_stats["snap_cache_read"],
        "snapshot_cache_creation_tokens": token_stats["snap_cache_write"],
        "snapshot_reasoning_tokens": token_stats["snap_reasoning"],
        "snapshot_context_tokens": snapshot_context,
        "snapshot_total_tokens": snapshot_context + token_stats["snap_output"],
        "usage": turn.usage,
    }


def _session_stats(turns: list[Turn]) -> dict[str, Any]:
    all_steps = [step for turn in turns for step in turn.steps]
    return {
        "all_steps": all_steps,
        "all_models": sorted({step.model for step in all_steps}),
        "all_agents": sorted({step.agent for step in all_steps if step.agent}),
        "total_tool_calls": sum(len(step.tools) for step in all_steps),
        "total_subagent_calls": sum(1 for step in all_steps for tool in step.tools if tool.is_subagent),
        "total_incomplete_steps": sum(1 for step in all_steps if step.incomplete),
        "tool_success": sum(1 for step in all_steps for tool in step.tools if not tool.error),
        "tool_error": sum(1 for step in all_steps for tool in step.tools if tool.error),
        "token_stats": _compute_token_summary(turns),
    }


def _session_metadata(session_meta: SessionMeta, turns: list[Turn]) -> dict[str, Any]:
    stats = _session_stats(turns)
    all_steps = stats["all_steps"]
    token_stats = stats["token_stats"]
    session_duration_ms = session_meta.time_updated - session_meta.time_created
    snapshot_context = (
        token_stats["snap_input"]
        + token_stats["snap_cache_read"]
        + token_stats["snap_cache_write"]
    )
    return {
        "session_id": session_meta.session_id,
        "session_title": session_meta.title,
        "session_directory": session_meta.directory,
        "session_duration_ms": session_duration_ms,
        "session_version": session_meta.version,
        "source": "opencode",
        "models": stats["all_models"],
        "agents": stats["all_agents"],
        "total_turns": len(turns),
        "total_llm_calls": len(all_steps),
        "total_tool_calls": stats["total_tool_calls"],
        "total_subagent_calls": stats["total_subagent_calls"],
        "incomplete_steps": stats["total_incomplete_steps"],
        "tool_success": stats["tool_success"],
        "tool_error": stats["tool_error"],
        "token_accounting": "per-step",
        "api_billed_input_tokens": token_stats["total_input"],
        "api_billed_output_tokens": token_stats["total_output"],
        "api_billed_cache_read_tokens": token_stats["total_cache_read"],
        "api_billed_cache_creation_tokens": token_stats["total_cache_write"],
        "api_billed_reasoning_tokens": token_stats["total_reasoning"],
        "incremental_input_tokens": token_stats["total_input"],
        "incremental_output_tokens": token_stats["total_output"],
        "incremental_cache_read_tokens": token_stats["total_cache_read"],
        "incremental_cache_creation_tokens": token_stats["total_cache_write"],
        "incremental_reasoning_tokens": token_stats["total_reasoning"],
        "snapshot_input_tokens": token_stats["snap_input"],
        "snapshot_output_tokens": token_stats["snap_output"],
        "snapshot_cache_read_tokens": token_stats["snap_cache_read"],
        "snapshot_cache_creation_tokens": token_stats["snap_cache_write"],
        "snapshot_reasoning_tokens": token_stats["snap_reasoning"],
        "snapshot_context_tokens": snapshot_context,
        "snapshot_total_tokens": snapshot_context + token_stats["snap_output"],
    }


def _session_output(session_meta: SessionMeta, turns: list[Turn]) -> dict[str, Any]:
    data = _session_metadata(session_meta=session_meta, turns=turns)
    data.pop("session_id", None)
    data.pop("source", None)
    data.pop("models", None)
    return data


def _tool_size_estimate(tool_name: str, tool_input: Any, tool_output: str) -> dict[str, int]:
    input_tokens_est = estimate_tokens(
        tool_name + json.dumps(tool_input, ensure_ascii=False) if tool_input else tool_name
    )
    output_tokens_est = estimate_tokens(tool_output)
    return {
        "input_tokens_est": input_tokens_est,
        "output_tokens_est": output_tokens_est,
        "total_tokens_est": input_tokens_est + output_tokens_est,
    }


# ── Opik Upload ─────────────────────────────────────────────────


def _upload_turns_sdk(
    turns: list[Turn],
    conn: sqlite3.Connection,
    parent_span: Any,
    session_id: str = "",
    depth: int = 0,
    is_child: bool = False,
) -> None:
    """Upload turns under a parent span/trace (SDK) using turn → round → llm → tool."""

    for turn in turns:
        turn_start = parse_ts(turn.timestamp)
        turn_end = _compute_turn_end(turn)

        user_content = [{"type": "text", "text": turn.user_input}]
        prefix = "sub-" if is_child else ""

        turn_span = parent_span.span(
            name=f"{prefix}turn-{turn.turn_index}" + (" [system]" if turn.is_system else ""),
            type="general",
            start_time=turn_start,
            input={"messages": [{"role": "user", "content": user_content}]},
            tags=(["sub-agent", "turn"] if is_child else ["opencode"])
                  + [f"{prefix}turn-{turn.turn_index}"]
                  + (["system-notification"] if turn.is_system else ["user-input"])
                  + sorted(turn.models) + sorted(turn.agents),
            metadata=_turn_metadata(turn, session_id, depth),
        )

        conversation: list[dict[str, Any]] = [{"role": "user", "content": user_content}]

        for round_item in _group_reasoning_rounds(turn):
            first_step = round_item.step_items[0][1]
            round_start = parse_ts(first_step.timestamp)
            round_end = _round_end_time(round_item)
            round_name = f"round-{round_item.round_idx}"
            round_span = turn_span.span(
                name=round_name,
                type="general",
                start_time=round_start,
                input={"messages": list(conversation)},
                tags=["reasoning-round", round_name],
                metadata=_round_metadata(round_item, turn, session_id),
            )
            round_outputs: list[dict[str, Any]] = []

            for llm_idx, step in round_item.step_items:
                model_display = strip_model_date(step.model)
                usage_meta = build_usage_metadata(step.usage)
                llm_start = parse_ts(step.timestamp)
                llm_end = _step_llm_end_time(step)

                assistant_content: list[dict[str, Any]] = []
                if step.reasoning:
                    assistant_content.append({"type": "thinking", "thinking": step.reasoning})
                if step.text:
                    assistant_content.append({"type": "text", "text": step.text})
                for tool in step.tools:
                    assistant_content.append({
                        "type": "tool_call",
                        "name": tool.name,
                        "args": tool.input,
                        "id": tool.call_id,
                    })

                llm_span = round_span.span(
                    name=model_display or step.model,
                    type="llm",
                    start_time=llm_start,
                    input={"messages": list(conversation)},
                    tags=[model_display, step.agent or "unknown-agent"],
                    model=model_display,
                    provider="anthropic",
                    usage=_opik_usage(usage_meta),
                    metadata={
                        "message_id": step.message_id,
                        "finish_reason": step.finish_reason,
                        "agent": step.agent,
                        "mode": step.mode,
                        "variant": step.variant,
                        "cost": step.cost,
                        "has_reasoning": bool(step.reasoning),
                        "raw_usage": step.usage,
                        "timing_version": "db-sequential-llm-to-tools",
                        "llm_index": llm_idx,
                        "round_idx": round_item.round_idx,
                    },
                )

                llm_outputs = [{"role": "assistant", "content": assistant_content}]
                llm_output_dict: dict[str, Any] = {"messages": llm_outputs}
                if usage_meta:
                    llm_output_dict["usage_metadata"] = usage_meta
                llm_span.end(end_time=llm_end, output=llm_output_dict)

                assistant_message = {"role": "assistant", "content": assistant_content}
                conversation.append(assistant_message)
                round_outputs.append(assistant_message)

                for tool in step.tools:
                    tool_output = stringify_content(tool.output)
                    tool_start = parse_ts(tool.start_time)
                    tool_end = parse_ts(tool.end_time)
                    if tool_end < tool_start:
                        tool_end = tool_start

                    if tool.is_subagent:
                        tool_input_display = {
                            "description": tool.child_description,
                            "prompt": tool.child_prompt,
                            "agent": tool.child_agent,
                            "model": tool.child_model,
                        } if tool.child_prompt else tool.input

                        tool_tags = ["sub-agent", tool.name]
                        if tool.child_agent:
                            tool_tags.append(f"agent:{tool.child_agent}")

                        agent_span = llm_span.span(
                            name=f"task:{tool.child_agent or tool.child_description[:30]}",
                            type="general",
                            start_time=tool_start,
                            input=tool_input_display,
                            tags=tool_tags,
                            metadata={
                                "is_subagent": True,
                                "status": tool.status,
                                "title": tool.title,
                                "tool_call_id": tool.call_id,
                                "child_session_id": tool.child_session_id,
                                "child_agent": tool.child_agent,
                                "child_model": tool.child_model,
                                "child_provider": tool.child_provider,
                                "child_description": tool.child_description,
                            },
                        )

                        if tool.child_session_id and depth < 5 and session_exists(conn, tool.child_session_id):
                            child_turns, _ = parse_turns(conn, tool.child_session_id)
                            if child_turns:
                                _upload_turns_sdk(
                                    turns=child_turns,
                                    conn=conn,
                                    parent_span=agent_span,
                                    session_id=tool.child_session_id,
                                    depth=depth + 1,
                                    is_child=True,
                                )

                        agent_span.end(
                            end_time=tool_end,
                            output={"result": tool_output},
                            error_info=_make_error_info(tool.error),
                        )
                    else:
                        size_estimate = _tool_size_estimate(tool.name, tool.input, tool_output)
                        tool_span = llm_span.span(
                            name=tool.name,
                            type="tool",
                            start_time=tool_start,
                            input={"tool_call_id": tool.call_id, "input": tool.input},
                            tags=["tool", tool.name],
                            metadata={
                                "status": tool.status,
                                "title": tool.title,
                                "tool_call_id": tool.call_id,
                                "tool_metadata": tool.tool_metadata,
                                "size_estimate": size_estimate,
                            },
                        )
                        tool_span.end(
                            end_time=tool_end,
                            output={"output": tool_output},
                            error_info=_make_error_info(tool.error),
                        )

                    tool_message = {
                        "role": "tool",
                        "tool_call_id": tool.call_id,
                        "content": [{"type": "text", "text": tool_output}],
                    }
                    conversation.append(tool_message)
                    round_outputs.append(tool_message)

            round_span.end(end_time=round_end, output={"messages": round_outputs})

        # ── Complete turn span ──
        turn_final_outputs = [m for m in conversation if m.get("role") != "user"]
        turn_span.end(
            end_time=turn_end,
            output={
                "messages": turn_final_outputs,
                "final_output": turn.final_output,
                "usage": turn.usage,
            },
        )

        if not is_child:
            sub_count = sum(1 for s in turn.steps for t in s.tools if t.is_subagent)
            sub_tag = f" ({sub_count} subagent)" if sub_count else ""
            print(f"  turn {turn.turn_index}: {len(turn.steps)} steps, "
                  f"{sum(len(s.tools) for s in turn.steps)} tools{sub_tag}")


def upload_turns_opik(
    turns: list[Turn],
    session_meta: SessionMeta,
    conn: sqlite3.Connection,
    session_name: str | None = None,
    project_name: str = "opencode-sessions",
) -> None:
    try:
        from opik import Opik
    except ImportError:
        print("ERROR: pip install opik")
        sys.exit(1)

    client = Opik(project_name=project_name)

    if not turns:
        print("No turns to upload")
        return

    display_name = session_name or f"opencode-{session_meta.session_id[:8]}-{session_meta.title[:48]}".strip("-")

    # Session start/end from turn timestamps
    session_start = parse_ts(turns[0].timestamp)
    last_turn = turns[-1]
    session_end_ts = step_end_timestamp(last_turn.steps[-1]) if last_turn.steps else last_turn.timestamp
    session_end = parse_ts(session_end_ts)
    if session_end < session_start:
        session_end = session_start

    stats = _session_stats(turns)
    all_models = stats["all_models"]
    all_agents = stats["all_agents"]
    total_tool_calls = stats["total_tool_calls"]
    total_subagent_calls = stats["total_subagent_calls"]
    tool_success = stats["tool_success"]
    tool_error = stats["tool_error"]
    token_stats = stats["token_stats"]

    # ── Session-level root trace ──
    trace = client.trace(
        name=display_name,
        start_time=session_start,
        input={
            "session_id": session_meta.session_id,
            "title": session_meta.title,
            "directory": session_meta.directory,
            "turns": len(turns),
        },
        tags=[
            "opencode",
            "sqlite-import",
            *[f"model:{m}" for m in all_models],
            *all_agents,
        ],
        metadata=_session_metadata(session_meta=session_meta, turns=turns),
    )

    _upload_turns_sdk(
            turns=turns,
            conn=conn,
            parent_span=trace,
            session_id=session_meta.session_id,
            depth=0,
            is_child=False,
        )

    # ── Close root trace ──
    trace.end(
        end_time=session_end,
        output=_session_output(session_meta=session_meta, turns=turns),
    )

    # Flush to ensure all data is sent
    client.flush()

    print(f"\nUploaded {len(turns)} turn(s) to Opik project '{project_name}'")
    _print_token_summary(token_stats, indent="  ")
    print(f"  tools: {tool_success} ok / {tool_error} error")


def _upload_turns_rest(
    turns: list[Turn],
    conn: sqlite3.Connection,
    client: Any,
    trace_id: str,
    parent_span_id: str | None,
    session_id: str,
    project_name: str,
    depth: int = 0,
    is_child: bool = False,
) -> None:
    """Upload turns via REST API using turn → round → llm → tool."""

    for turn in turns:
        turn_start = parse_ts(turn.timestamp)
        turn_end = _compute_turn_end(turn)

        turn_span_id = id_helpers.generate_id(turn_start)
        user_content = [{"type": "text", "text": turn.user_input}]
        conversation: list[dict[str, Any]] = [{"role": "user", "content": user_content}]
        prefix = "sub-" if is_child else ""

        create_kwargs: dict[str, Any] = {
            "id": turn_span_id,
            "trace_id": trace_id,
            "project_name": project_name,
            "name": f"{prefix}turn-{turn.turn_index}" + (" [system]" if turn.is_system else ""),
            "type": "general",
            "start_time": turn_start,
            "end_time": turn_end,
            "input": {"messages": [{"role": "user", "content": user_content}]},
            "output": {},
            "metadata": _turn_metadata(turn, session_id, depth),
        }
        if parent_span_id:
            create_kwargs["parent_span_id"] = parent_span_id
        client.rest_client.spans.create_span(**create_kwargs)

        for round_item in _group_reasoning_rounds(turn):
            first_step = round_item.step_items[0][1]
            round_start = parse_ts(first_step.timestamp)
            round_end = _round_end_time(round_item)
            round_span_id = id_helpers.generate_id(round_start)
            client.rest_client.spans.create_span(
                id=round_span_id,
                trace_id=trace_id,
                parent_span_id=turn_span_id,
                project_name=project_name,
                name=f"round-{round_item.round_idx}",
                type="general",
                start_time=round_start,
                end_time=round_end,
                input={"messages": list(conversation)},
                output={},
                metadata=_round_metadata(round_item, turn, session_id),
                tags=["reasoning-round", f"round-{round_item.round_idx}"],
            )
            round_outputs: list[dict[str, Any]] = []

            for llm_idx, step in round_item.step_items:
                model_display = strip_model_date(step.model)
                usage_meta = build_usage_metadata(step.usage)
                llm_usage = _opik_usage_ints(usage_meta)
                llm_start = parse_ts(step.timestamp)
                llm_end = _step_llm_end_time(step)

                assistant_content: list[dict[str, Any]] = []
                if step.reasoning:
                    assistant_content.append({"type": "thinking", "thinking": step.reasoning})
                if step.text:
                    assistant_content.append({"type": "text", "text": step.text})
                for tool in step.tools:
                    assistant_content.append({
                        "type": "tool_call",
                        "name": tool.name,
                        "args": tool.input,
                        "id": tool.call_id,
                    })

                llm_span_id = id_helpers.generate_id(llm_start)
                llm_output_payload = {
                    "messages": [{"role": "assistant", "content": assistant_content}],
                    **({"usage_metadata": usage_meta} if usage_meta else {}),
                }
                client.rest_client.spans.create_span(
                    id=llm_span_id,
                    trace_id=trace_id,
                    parent_span_id=round_span_id,
                    project_name=project_name,
                    name=model_display or step.model,
                    type="llm",
                    start_time=llm_start,
                    end_time=llm_end,
                    input={"messages": list(conversation)},
                    output=llm_output_payload,
                    metadata={
                        "message_id": step.message_id,
                        "finish_reason": step.finish_reason,
                        "agent": step.agent,
                        "mode": step.mode,
                        "variant": step.variant,
                        "cost": step.cost,
                        "has_reasoning": bool(step.reasoning),
                        "raw_usage": step.usage,
                        "timing_version": "db-sequential-llm-to-tools",
                        "llm_index": llm_idx,
                        "round_idx": round_item.round_idx,
                    },
                    model=model_display,
                    provider="anthropic",
                    usage=llm_usage,
                )

                assistant_message = {"role": "assistant", "content": assistant_content}
                conversation.append(assistant_message)
                round_outputs.append(assistant_message)

                for tool in step.tools:
                    tool_output = stringify_content(tool.output)
                    tool_start = parse_ts(tool.start_time)
                    tool_end = parse_ts(tool.end_time)
                    if tool_end < tool_start:
                        tool_end = tool_start

                    if tool.is_subagent:
                        agent_span_id = id_helpers.generate_id(tool_start)
                        client.rest_client.spans.create_span(
                            id=agent_span_id,
                            trace_id=trace_id,
                            parent_span_id=llm_span_id,
                            project_name=project_name,
                            name=f"task:{tool.child_agent or tool.child_description[:30]}",
                            type="general",
                            start_time=tool_start,
                            end_time=tool_end,
                            input={
                                "description": tool.child_description,
                                "prompt": tool.child_prompt,
                                "agent": tool.child_agent,
                                "model": tool.child_model,
                            } if tool.child_prompt else {"input": tool.input},
                            output={"result": tool_output},
                            metadata={
                                "is_subagent": True,
                                "child_session_id": tool.child_session_id,
                                "child_agent": tool.child_agent,
                                "child_model": tool.child_model,
                                "child_provider": tool.child_provider,
                                "child_description": tool.child_description,
                            },
                            error_info=_make_error_info(tool.error),
                        )

                        if tool.child_session_id and depth < 5 and session_exists(conn, tool.child_session_id):
                            child_turns, _ = parse_turns(conn, tool.child_session_id)
                            if child_turns:
                                _upload_turns_rest(
                                    turns=child_turns,
                                    conn=conn,
                                    client=client,
                                    trace_id=trace_id,
                                    parent_span_id=agent_span_id,
                                    session_id=tool.child_session_id,
                                    project_name=project_name,
                                    depth=depth + 1,
                                    is_child=True,
                                )
                    else:
                        size_estimate = _tool_size_estimate(tool.name, tool.input, tool_output)
                        tool_span_id = id_helpers.generate_id(tool_start)
                        client.rest_client.spans.create_span(
                            id=tool_span_id,
                            trace_id=trace_id,
                            parent_span_id=llm_span_id,
                            project_name=project_name,
                            name=tool.name,
                            type="tool",
                            start_time=tool_start,
                            end_time=tool_end,
                            input={"tool_call_id": tool.call_id, "input": tool.input},
                            output={"output": tool_output},
                            metadata={
                                "status": tool.status,
                                "title": tool.title,
                                "tool_call_id": tool.call_id,
                                "tool_metadata": tool.tool_metadata,
                                "size_estimate": size_estimate,
                            },
                            error_info=_make_error_info(tool.error),
                        )

                    tool_message = {
                        "role": "tool",
                        "tool_call_id": tool.call_id,
                        "content": [{"type": "text", "text": tool_output}],
                    }
                    conversation.append(tool_message)
                    round_outputs.append(tool_message)

            client.rest_client.spans.update_span(
                round_span_id,
                trace_id=trace_id,
                project_name=project_name,
                parent_span_id=turn_span_id,
                output={"messages": round_outputs},
            )

        # Update turn span with final outputs
        update_kwargs: dict[str, Any] = {
            "trace_id": trace_id,
            "project_name": project_name,
            "output": {
                "messages": [m for m in conversation if m.get("role") != "user"],
                "final_output": turn.final_output,
                "usage": turn.usage,
            },
        }
        if parent_span_id:
            update_kwargs["parent_span_id"] = parent_span_id
        client.rest_client.spans.update_span(turn_span_id, **update_kwargs)

        if not is_child:
            sub_count = sum(1 for s in turn.steps for t in s.tools if t.is_subagent)
            sub_tag = f" ({sub_count} subagent)" if sub_count else ""
            print(f"  turn {turn.turn_index}: {len(turn.steps)} steps, "
                  f"{sum(len(s.tools) for s in turn.steps)} tools{sub_tag}")


def upload_turns_opik_sync(
    turns: list[Turn],
    session_meta: SessionMeta,
    conn: sqlite3.Connection,
    session_name: str | None = None,
    project_name: str = "opencode-sessions",
) -> None:
    """Upload traces synchronously via direct REST API calls."""
    try:
        from opik import Opik
    except ImportError:
        print("ERROR: pip install opik")
        sys.exit(1)

    client = Opik(project_name=project_name)
    if not turns:
        print("No turns to upload")
        return

    display_name = session_name or f"opencode-{session_meta.session_id[:8]}-{session_meta.title[:48]}".strip("-")

    session_start = parse_ts(turns[0].timestamp)
    last_turn = turns[-1]
    session_end_ts = step_end_timestamp(last_turn.steps[-1]) if last_turn.steps else last_turn.timestamp
    session_end = parse_ts(session_end_ts)
    if session_end < session_start:
        session_end = session_start

    stats = _session_stats(turns)
    all_models = stats["all_models"]
    all_agents = stats["all_agents"]
    total_tool_calls = stats["total_tool_calls"]
    total_subagent_calls = stats["total_subagent_calls"]
    tool_success = stats["tool_success"]
    tool_error = stats["tool_error"]
    token_stats = stats["token_stats"]

    trace_id = id_helpers.generate_id(session_start)

    client.rest_client.traces.create_trace(
        id=trace_id,
        project_name=project_name,
        name=display_name,
        start_time=session_start,
        end_time=session_end,
        input={
            "session_id": session_meta.session_id,
            "title": session_meta.title,
            "directory": session_meta.directory,
            "turns": len(turns),
        },
        output=_session_output(session_meta=session_meta, turns=turns),
        metadata=_session_metadata(session_meta=session_meta, turns=turns),
        tags=["opencode", "sqlite-import", *[f"model:{m}" for m in all_models], *all_agents],
        thread_id=session_meta.session_id,
    )

    _upload_turns_rest(
        turns=turns,
        conn=conn,
        client=client,
        trace_id=trace_id,
        parent_span_id=None,
        session_id=session_meta.session_id,
        project_name=project_name,
        depth=0,
        is_child=False,
    )

    # Verify upload
    found = client.search_traces(
        project_name=project_name,
        filter_string=f'input contains "{session_meta.session_id}"',
        max_results=20,
        wait_for_at_least=1,
        wait_for_timeout=20,
    )
    if not found:
        print(f"ERROR: trace for session {session_meta.session_id} not found after sync upload")
        sys.exit(1)

    print(f"\nUploaded {len(turns)} turn(s) to Opik project '{project_name}'")
    _print_token_summary(token_stats, indent="  ")
    print(f"  tools: {tool_success} ok / {tool_error} error")


# ── Main ─────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Upload OpenCode SQLite session to Opik (post-run)")
    parser.add_argument("--db", required=True, help="Path to opencode.db")
    parser.add_argument("--session-id", default=None,
                        help="Import a specific OpenCode session id")
    parser.add_argument("--project", "-p",
                        default=os.getenv("OPIK_PROJECT") or "opencode-sessions",
                        help="Opik project name")
    parser.add_argument("--session-name", default=None,
                        help="Root trace display name")
    parser.add_argument(
        "--transport",
        choices=("sdk", "sync-rest"),
        default="sdk",
        help="Upload transport. Use 'sync-rest' for reliable direct REST writes.",
    )
    parser.add_argument(
        "--timestamp-mode",
        choices=["original", "shift-to-now"],
        default="original",
        help="Keep original session timestamps or shift the whole trace near the current time",
    )
    parser.add_argument("--dry-run", action="store_true", help="Preview only")
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: {db_path} not found")
        sys.exit(1)

    conn = open_db(db_path)
    try:
        turns, session_meta = parse_turns(conn, args.session_id)
        if not turns:
            print("No turns found")
            sys.exit(3)

        configure_timestamp_shift(args.timestamp_mode, turns[0].timestamp)

        if args.dry_run:
            preview(turns, session_meta, conn)
            return

        session_name = (
            args.session_name
            or f"opencode-{session_meta.session_id[:8]}-{session_meta.title[:48]}".strip("-")
        )

        print(f"Uploading session {session_meta.session_id} ({len(turns)} turns)")

        uploader = upload_turns_opik_sync if args.transport == "sync-rest" else upload_turns_opik
        uploader(
            turns=turns,
            session_meta=session_meta,
            conn=conn,
            session_name=session_name,
            project_name=args.project,
        )
    finally:
        conn.close()


if __name__ == "__main__":
    main()
