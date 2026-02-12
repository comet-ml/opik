"""End-to-end llm_episode demo for telemetry-derived budget enforcement.

This example demonstrates how to convert runtime observability data into
episode-level budgets:
1) Simulate a multi-turn run.
2) Query traces/spans for that thread.
3) Roll up token usage, latency, and estimated cost.
4) Gate the episode with explicit budget assertions in EpisodeBudgets.
"""

import os
import time
from datetime import datetime
from typing import Any

import pytest

import opik
from opik import exceptions
from opik import id_helpers
from opik import llm_episode
import opik.opik_context as opik_context
from opik.simulation import (
    EpisodeAssertion,
    EpisodeBudgetMetric,
    EpisodeBudgets,
    EpisodeResult,
    EpisodeScore,
    SimulatedUser,
    build_trajectory_summary,
    make_max_turns_assertion,
    make_tool_call_budget,
    run_simulation,
)
from demo_helpers import get_demo_logger, log_episode_panel, log_json_debug

LOGGER = get_demo_logger("pytest_integration_e2e.observability")


class ObservabilityAwareAgent:
    """Deterministic app that attaches synthetic usage/cost on each turn.

    The payloads are intentionally synthetic so the example is reproducible while
    still mirroring the fields available in real Opik traces/spans.
    """

    def __init__(self) -> None:
        self.trajectory_by_thread: dict[str, list[dict[str, Any]]] = {}
        self.local_telemetry_by_thread: dict[str, dict[str, float]] = {}

    def _record(self, thread_id: str, action: str, details: dict[str, Any]) -> None:
        self.trajectory_by_thread.setdefault(thread_id, []).append(
            {"action": action, "details": details}
        )

    @opik.track(name="observability_aware_agent")
    def __call__(self, user_message: str, *, thread_id: str, **kwargs):
        """Emit deterministic content plus span usage/cost telemetry per turn."""
        run_tag = f"episode-observability-{thread_id[-8:]}"
        # Thread tags/metadata make downstream filtering reliable in CI and demos.
        opik_context.update_current_trace(
            thread_id=thread_id,
            tags=["simulation", "observability", "budgets", run_tag],
            metadata={"run_tag": run_tag},
        )

        normalized = user_message.lower()

        if "invoice" in normalized:
            prompt_tokens = 70
            completion_tokens = 45
            self._record(thread_id, "fetch_invoice_policy", {"doc_id": "BILL-17"})
            assistant = (
                "For invoice disputes, submit the dispute form within 30 days and include "
                "invoice number plus reason code."
            )
        else:
            prompt_tokens = 62
            completion_tokens = 38
            self._record(thread_id, "fetch_support_playbook", {"doc_id": "SUP-11"})
            assistant = (
                "Use account recovery in settings. If recovery fails, support can verify your "
                "identity and unlock access."
            )

        total_tokens = prompt_tokens + completion_tokens
        estimated_cost = round(total_tokens * 0.0000008, 8)
        telemetry = self.local_telemetry_by_thread.setdefault(
            thread_id,
            {
                "prompt_tokens": 0.0,
                "completion_tokens": 0.0,
                "total_tokens": 0.0,
                "total_cost_usd": 0.0,
            },
        )
        telemetry["prompt_tokens"] += float(prompt_tokens)
        telemetry["completion_tokens"] += float(completion_tokens)
        telemetry["total_tokens"] += float(total_tokens)
        telemetry["total_cost_usd"] += float(estimated_cost)

        # Populate the same telemetry fields used by production observability flows.
        opik_context.update_current_span(
            usage={
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": total_tokens,
            },
            model="gpt-5-nano",
            provider="openai",
            total_cost=estimated_cost,
            metadata={"telemetry_source": "example_synthetic_usage"},
        )

        return {"role": "assistant", "content": assistant}


def observability_scenarios() -> list[dict[str, Any]]:
    """Single scenario focused on token/latency/cost budget gating."""
    return [
        {
            "scenario_id": "observability_billing_v1",
            "project_name": "pytest-episode-e2e",
            "tags": ["simulation", "observability", "budgets"],
            "max_turns": 2,
            "tool_call_limit": 3,
            "token_limit": 300,
            "latency_limit_ms": 60000,
            "cost_limit_usd": 0.01,
            "user_persona": "You ask for account help and a billing policy summary.",
            "user_messages": [
                "I need help recovering account access.",
                "Also, what is your invoice dispute policy?",
            ],
        }
    ]


def _duration_ms_from_span(span: Any) -> float:
    """Extract span duration in milliseconds from datetime or numeric fields."""
    start = getattr(span, "start_time", None)
    end = getattr(span, "end_time", None)

    if isinstance(start, datetime) and isinstance(end, datetime):
        return max((end - start).total_seconds() * 1000.0, 0.0)

    duration = getattr(span, "duration", None)
    if duration is None:
        return 0.0

    try:
        return max(float(duration), 0.0)
    except (TypeError, ValueError):
        return 0.0


def _rollup_thread_telemetry(
    project_name: str, thread_id: str, run_tag: str, require_telemetry: bool = False
) -> dict[str, Any]:
    """Query and aggregate trace/span telemetry for one simulation thread.

    Returns rollups used by EpisodeBudgets:
    - token usage (prompt/completion/total)
    - latency
    - estimated cost
    """
    # Runtime traces usually land in OPIK_PROJECT_NAME; scenario project_name is metadata.
    env_project_name = os.getenv("OPIK_PROJECT_NAME")
    project_candidates = []
    for candidate in (env_project_name, project_name, None):
        if candidate not in project_candidates:
            project_candidates.append(candidate)

    selected_project_name = project_candidates[0]
    client = (
        opik.Opik(project_name=selected_project_name)
        if selected_project_name
        else opik.Opik()
    )
    # Ensure async trace/span messages are sent before querying telemetry.
    client.flush(timeout=20)
    traces = []
    spans = []

    # Try each candidate project so local/demo setups are resilient.
    for candidate_project_name in project_candidates:
        if traces:
            break

        try:
            if require_telemetry:
                traces = client.search_traces(
                    project_name=candidate_project_name,
                    filter_string=f'thread_id = "{thread_id}"',
                    max_results=1000,
                    truncate=False,
                    wait_for_at_least=1,
                    wait_for_timeout=15,
                )
            else:
                traces = client.search_traces(
                    project_name=candidate_project_name,
                    filter_string=f'thread_id = "{thread_id}"',
                    max_results=1000,
                    truncate=False,
                )
        except exceptions.SearchTimeoutError:
            traces = []
        except Exception:
            traces = []

        if traces:
            break

        try:
            if require_telemetry:
                traces = client.search_traces(
                    project_name=candidate_project_name,
                    filter_string=f'tags contains "{run_tag}"',
                    max_results=1000,
                    truncate=False,
                    wait_for_at_least=1,
                    wait_for_timeout=8,
                )
            else:
                traces = client.search_traces(
                    project_name=candidate_project_name,
                    filter_string=f'tags contains "{run_tag}"',
                    max_results=1000,
                    truncate=False,
                )
        except exceptions.SearchTimeoutError:
            traces = []
        except Exception:
            traces = []

        if traces:
            break

        deadline = time.time() + (8 if require_telemetry else 1.5)
        while time.time() < deadline and not traces:
            try:
                traces = client.search_traces(
                    project_name=candidate_project_name,
                    filter_string=f'thread_id = "{thread_id}"',
                    max_results=1000,
                    truncate=False,
                )
            except Exception:
                traces = []

            if not traces:
                try:
                    traces = client.search_traces(
                        project_name=candidate_project_name,
                        filter_string=f'tags contains "{run_tag}"',
                        max_results=1000,
                        truncate=False,
                    )
                except Exception:
                    traces = []
            if traces:
                break
            time.sleep(1)

    for trace in traces:
        trace_spans = client.search_spans(
            project_name=getattr(trace, "project_name", None) or selected_project_name,
            trace_id=trace.id,
            max_results=1000,
            truncate=False,
        )
        spans.extend(trace_spans)

    telemetry_records = spans if spans else traces
    telemetry_source = "spans" if spans else ("traces" if traces else "none")

    prompt_tokens = 0
    completion_tokens = 0
    total_tokens = 0
    total_cost = 0.0
    latency_ms = 0.0

    for record in telemetry_records:
        usage = getattr(record, "usage", None) or {}

        prompt_tokens += int(usage.get("prompt_tokens") or 0)
        completion_tokens += int(usage.get("completion_tokens") or 0)

        total_tokens_value = usage.get("total_tokens")
        if total_tokens_value is None:
            total_tokens_value = int(usage.get("prompt_tokens") or 0) + int(
                usage.get("completion_tokens") or 0
            )
        total_tokens += int(total_tokens_value or 0)

        span_cost = getattr(record, "total_estimated_cost", None)
        if span_cost is not None:
            total_cost += float(span_cost)

        latency_ms += _duration_ms_from_span(record)

    return {
        "traces_count": float(len(traces)),
        "spans_count": float(len(spans)),
        "telemetry_source": telemetry_source,
        "prompt_tokens": float(prompt_tokens),
        "completion_tokens": float(completion_tokens),
        "total_tokens": float(total_tokens),
        "total_cost_usd": float(round(total_cost, 10)),
        "latency_ms": float(round(latency_ms, 2)),
    }


def _local_telemetry_fallback(
    agent: ObservabilityAwareAgent, thread_id: str
) -> dict[str, Any]:
    local = agent.local_telemetry_by_thread.get(thread_id)
    if not local:
        return {}

    return {
        "traces_count": 0.0,
        "spans_count": 0.0,
        "telemetry_source": "local_fallback",
        "prompt_tokens": float(local["prompt_tokens"]),
        "completion_tokens": float(local["completion_tokens"]),
        "total_tokens": float(local["total_tokens"]),
        "total_cost_usd": float(round(local["total_cost_usd"], 10)),
        "latency_ms": 0.0,
    }


@pytest.mark.filterwarnings(
    "ignore:Test functions should return None:pytest.PytestReturnNotNoneWarning"
)
@pytest.mark.parametrize("scenario", observability_scenarios())
@llm_episode(scenario_id_key="scenario_id")
def test_llm_episode_budgeting_from_trace_span_telemetry(scenario):
    """Build episode budgets directly from Opik observability telemetry."""
    agent = ObservabilityAwareAgent()
    thread_id = id_helpers.generate_id()
    run_tag = f"episode-observability-{thread_id[-8:]}"
    tags = [*scenario["tags"], run_tag]

    # Run deterministic simulation and keep thread_id fixed for telemetry lookup.
    simulation = run_simulation(
        app=agent,
        user_simulator=SimulatedUser(
            persona=scenario["user_persona"], fixed_responses=scenario["user_messages"]
        ),
        max_turns=scenario["max_turns"],
        thread_id=thread_id,
        project_name=scenario["project_name"],
        tags=tags,
    )

    trajectory = agent.trajectory_by_thread.get(thread_id, [])
    require_telemetry = (
        os.getenv("OPIK_EXAMPLE_REQUIRE_TELEMETRY", "false").lower() == "true"
    )
    telemetry = _rollup_thread_telemetry(
        project_name=scenario["project_name"],
        thread_id=thread_id,
        run_tag=run_tag,
        require_telemetry=require_telemetry,
    )
    if telemetry["telemetry_source"] == "none":
        fallback = _local_telemetry_fallback(agent=agent, thread_id=thread_id)
        if fallback:
            telemetry = fallback
    telemetry_available = (
        telemetry["spans_count"] >= 1.0 or telemetry["traces_count"] >= 1.0
    )

    turn_assertion = make_max_turns_assertion(
        conversation_history=simulation["conversation_history"],
        max_turns=scenario["max_turns"],
        name="max_turns_guardrail",
    )

    episode = EpisodeResult(
        scenario_id=scenario["scenario_id"],
        thread_id=thread_id,
        assertions=[
            # Optional strict gate for environments where telemetry ingestion is required.
            EpisodeAssertion(
                name="telemetry_trace_or_span_discovered",
                passed=telemetry_available or not require_telemetry,
                reason=(
                    f"source={telemetry['telemetry_source']}, "
                    f"traces_count={telemetry['traces_count']:.0f}, "
                    f"spans_count={telemetry['spans_count']:.0f}, "
                    f"require_telemetry={require_telemetry}"
                ),
                severity="error" if require_telemetry else "warning",
            ),
            turn_assertion,
        ],
        scores=[
            # Example quality metric derived from usage budget headroom.
            EpisodeScore(
                name="token_efficiency",
                value=max(
                    0.0,
                    min(
                        1.0,
                        1.0
                        - telemetry["total_tokens"] / max(scenario["token_limit"], 1),
                    ),
                ),
                reason=(
                    f"total_tokens={telemetry['total_tokens']:.0f}, "
                    f"limit={scenario['token_limit']}"
                ),
            )
        ],
        budgets=EpisodeBudgets(
            # Mix built-in budgets plus custom cost budget from observability rollups.
            max_turns=EpisodeBudgetMetric(
                used=float(len(simulation["conversation_history"]) // 2),
                limit=float(scenario["max_turns"]),
                unit="count",
            ),
            tool_calls=make_tool_call_budget(
                trajectory=trajectory, limit=scenario["tool_call_limit"]
            ),
            token_usage=EpisodeBudgetMetric(
                used=float(telemetry["total_tokens"]),
                limit=float(scenario["token_limit"]),
                unit="tokens",
            ),
            latency_ms=EpisodeBudgetMetric(
                used=float(telemetry["latency_ms"]),
                limit=float(scenario["latency_limit_ms"]),
                unit="ms",
            ),
            custom={
                "total_estimated_cost_usd": EpisodeBudgetMetric(
                    used=float(telemetry["total_cost_usd"]),
                    limit=float(scenario["cost_limit_usd"]),
                    unit="usd",
                )
            },
        ),
        trajectory_summary=build_trajectory_summary(trajectory),
        metadata={
            "tags": tags,
            "thread_telemetry": telemetry,
        },
    )

    log_episode_panel(
        LOGGER,
        scenario_id=scenario["scenario_id"],
        thread_id=thread_id,
        episode=episode,
        turns=len(simulation["conversation_history"]) // 2,
        tool_calls=episode.trajectory_summary.get("tool_calls_count"),
        extras={
            "Telemetry source": telemetry["telemetry_source"],
            "Tokens": f"{telemetry['total_tokens']:.0f}",
            "Cost (USD)": f"{telemetry['total_cost_usd']:.8f}",
            "Latency (ms)": f"{telemetry['latency_ms']:.2f}",
        },
    )
    log_json_debug(LOGGER, "observability_budget_episode", episode.model_dump())

    assert episode.is_passing(), episode.model_dump_json(indent=2)
    return episode
