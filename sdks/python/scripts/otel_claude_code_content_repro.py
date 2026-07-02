# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "claude-agent-sdk>=0.2.100",
#     "opik",
# ]
# ///
"""Reproduction for OPIK-6993 — OTEL ingest drops AI output and tool I/O from
Claude Agent SDK / Claude Code traces.

Ticket: https://comet-ml.atlassian.net/browse/OPIK-6993
Docs:   integrations/claude-agent-sdk (the documented Claude Code telemetry pattern)

This follows the documented integration exactly: set Claude Code's OTEL telemetry env
vars pointing at Opik, then run a Claude Agent SDK query. The agent is forced to make
several tool calls (a Bash `ls` + a file Read). Open the resulting trace in Opik to see
the reported symptoms: user prompt buried in an attribute-bag, AI output missing, tool
input/output not rendered as clean tool I/O.

Run:
    uv run scripts/otel_claude_code_content_repro.py

Needs the `claude` CLI installed + authenticated. Opik credentials (api_key, workspace,
url_override) are read from ~/.opik.config, overridable via OPIK_API_KEY /
OPIK_WORKSPACE / OPIK_URL_OVERRIDE. Traces are logged to project 'otel-claude-code-repro'
(override with OPIK_PROJECT_NAME).
"""

import asyncio
import json
import os
import tempfile
from pathlib import Path

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ResultMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
    query,
)
from opik import Opik
from opik.config import OpikConfig

MARKER = "OTEL-REPRO-MARKER-12345"


def opik_settings():
    """Read the four values we need from ~/.opik.config.

    OpikConfig loads ~/.opik.config and also honors OPIK_* env overrides
    (OPIK_API_KEY / OPIK_WORKSPACE / OPIK_URL_OVERRIDE / OPIK_PROJECT_NAME).
    """
    cfg = OpikConfig()
    missing = [
        name
        for name in ("api_key", "workspace", "url_override")
        if not getattr(cfg, name)
    ]
    if missing:
        raise SystemExit(
            f"Missing {', '.join(missing)} in ~/.opik.config — run `opik configure`."
        )
    return {
        "api_key": cfg.api_key,
        "workspace": cfg.workspace,
        "base": cfg.url_override.rstrip("/"),
        "project": os.environ.get("OPIK_PROJECT_NAME", "otel-claude-code-repro"),
    }


def telemetry_env(s):
    otel_base = f"{s['base']}/v1/private/otel"
    return {
        # --- documented Claude Code telemetry pattern ---
        "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
        # Required for Claude Code to emit trace SPANS (not just metrics/logs).
        # Opik only ingests spans, so without this nothing appears in Opik.
        "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA": "1",
        "OTEL_TRACES_EXPORTER": "otlp",
        "OTEL_LOGS_EXPORTER": "otlp",
        "OTEL_METRICS_EXPORTER": "otlp",
        "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
        "OTEL_EXPORTER_OTLP_ENDPOINT": otel_base,
        "OTEL_EXPORTER_OTLP_HEADERS": (
            f"Authorization={s['api_key']},"
            f"Comet-Workspace={s['workspace']},"
            f"projectName={s['project']}"
        ),
        # un-redact content so we prove the drop even with every gate ON
        "OTEL_LOG_USER_PROMPTS": "1",
        "OTEL_LOG_TOOL_DETAILS": "1",
        "OTEL_LOG_TOOL_CONTENT": "1",
        # Assistant response text. Documented, but emitted on the logs/events signal,
        # which Opik has no receiver for — so this alone does NOT surface AI output in Opik.
        "OTEL_LOG_ASSISTANT_RESPONSES": "1",
        # --- detailed-tracing beta ---
        # Makes Claude Code attach content to spans: response.model_output (AI text) on
        # claude_code.llm_request, tool_input, and tool results. Gated by Ew() in the CLI:
        # (ENABLE_BETA_TRACING_DETAILED && BETA_TRACING_ENDPOINT) && (!interactive || statsig).
        # The Agent SDK runs non-interactive, so the statsig flag is bypassed. The beta
        # exporter posts to `${BETA_TRACING_ENDPOINT}/v1/traces`, so point it at Opik.
        "ENABLE_BETA_TRACING_DETAILED": "1",
        "BETA_TRACING_ENDPOINT": otel_base,
    }


def build_prompt(sample_file):
    return (
        "Do the following using tools, one step at a time:\n"
        "1. Use the Bash tool to run `ls` in the current directory.\n"
        f"2. Use the Read tool to read the file {sample_file}.\n"
        "Then reply with a single sentence stating the marker value found in the file."
    )


async def run_agent(s, sample_file):
    options = ClaudeAgentOptions(
        allowed_tools=["Bash", "Read"],
        permission_mode="bypassPermissions",
        env=telemetry_env(s),
    )
    print(f"OTLP endpoint: {s['base']}/v1/private/otel")
    print(
        f"Running agent (telemetry -> Opik workspace '{s['workspace']}', "
        f"project '{s['project']}', marker {MARKER})...\n"
    )
    async for message in query(prompt=build_prompt(sample_file), options=options):
        if isinstance(message, AssistantMessage):
            for block in message.content:
                if isinstance(block, TextBlock):
                    print(f"[assistant] {block.text.strip()}")
                elif isinstance(block, ToolUseBlock):
                    print(f"[tool-use]  {block.name} {block.input}")
        elif isinstance(message, UserMessage):
            for block in message.content:
                if isinstance(block, ToolResultBlock):
                    print(f"[tool-out]  {str(block.content)[:200]}")
        elif isinstance(message, ResultMessage):
            print(f"[result]    {getattr(message, 'result', '')}")
    print("\nAgent run complete.")


def fetch_trace(s, unique):
    """Fetch the trace just logged and report whether the detailed-beta content
    (response.model_output = AI text, tool_input) actually reached Opik on a span.
    """
    client = Opik()
    print(f"\nFetching trace where input contains '{unique}' ...")
    try:
        traces = client.search_traces(
            project_name=s["project"],
            filter_string=f'input contains "{unique}"',
            max_results=5,
            wait_for_at_least=1,
            wait_for_timeout=30,
        )
    except Exception as exc:
        print(f"  Trace not found yet ({exc}). OTEL ingest can lag — check the UI.")
        return

    trace = traces[0]
    print(f"  Found trace id: {trace.id}  (name: {trace.name})")

    spans = client.search_spans(
        project_name=s["project"], trace_id=trace.id, max_results=100
    )
    blob = json.dumps([s.__dict__ for s in spans], default=str)
    checks = {
        "AI output (response.model_output)": "response.model_output" in blob,
        "tool_input attribute": '"tool_input"' in blob,
        "tool.output event (metadata)": "tool.output" in blob,
    }
    print("\n  Did the detailed-beta content reach Opik on spans?")
    for label, present in checks.items():
        print(f"    [{'YES ' if present else 'NO  '}] {label}")


async def main():
    s = opik_settings()
    with tempfile.TemporaryDirectory() as tmp:
        sample_file = Path(tmp) / "repro_sample.txt"
        sample_file.write_text(f"marker: {MARKER}\n")
        unique = Path(tmp).name
        await run_agent(s, str(sample_file))
    fetch_trace(s, unique)
    print(f"\nOpen Opik project '{s['project']}' and search for {MARKER}.")


if __name__ == "__main__":
    asyncio.run(main())
