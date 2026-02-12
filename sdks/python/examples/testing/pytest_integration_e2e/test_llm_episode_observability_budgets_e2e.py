import os
import time
from datetime import datetime
from typing import Any

import pytest

import opik
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
    """Deterministic app that attaches synthetic usage/cost on each turn."""

    def __init__(self) -> None:
        self.trajectory_by_thread: dict[str, list[dict[str, Any]]] = {}

    def _record(self, thread_id: str, action: str, details: dict[str, Any]) -> None:
        self.trajectory_by_thread.setdefault(thread_id, []).append(
            {"action": action, "details": details}
        )

    @opik.track(name="observability_aware_agent")
    def __call__(self, user_message: str, *, thread_id: str, **kwargs):
        run_tag = f"episode-observability-{thread_id[-8:]}"
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
    project_name: str, thread_id: str, run_tag: str
) -> dict[str, float]:
    # Project used by runtime tracing is controlled by OPIK_PROJECT_NAME.
    # If unset, let Opik default to its configured project.
    configured_project_name = os.getenv("OPIK_PROJECT_NAME")
    client = (
        opik.Opik(project_name=configured_project_name)
        if configured_project_name
        else opik.Opik()
    )
    # Ensure async trace/span messages are sent before querying telemetry.
    client.flush(timeout=20)
    traces = []
    deadline = time.time() + 8
    while time.time() < deadline and not traces:
        traces = client.search_traces(
            project_name=configured_project_name,
            filter_string=f'thread_id = "{thread_id}"',
            max_results=1000,
            truncate=False,
        )
        if not traces:
            traces = client.search_traces(
                project_name=configured_project_name,
                filter_string=f'tags contains "{run_tag}"',
                max_results=1000,
                truncate=False,
            )
        if traces:
            break
        time.sleep(1)

    spans = []
    for trace in traces:
        trace_spans = client.search_spans(
            project_name=configured_project_name,
            trace_id=trace.id,
            max_results=1000,
            truncate=False,
        )
        spans.extend(trace_spans)

    telemetry_records = spans if spans else traces

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
        "telemetry_source": "spans" if spans else "traces",
        "prompt_tokens": float(prompt_tokens),
        "completion_tokens": float(completion_tokens),
        "total_tokens": float(total_tokens),
        "total_cost_usd": float(round(total_cost, 10)),
        "latency_ms": float(round(latency_ms, 2)),
    }


@pytest.mark.filterwarnings(
    "ignore:Test functions should return None:pytest.PytestReturnNotNoneWarning"
)
@pytest.mark.parametrize("scenario", observability_scenarios())
@llm_episode(scenario_id_key="scenario_id")
def test_llm_episode_budgeting_from_trace_span_telemetry(scenario):
    agent = ObservabilityAwareAgent()
    thread_id = id_helpers.generate_id()
    run_tag = f"episode-observability-{thread_id[-8:]}"
    tags = [*scenario["tags"], run_tag]

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
    telemetry = _rollup_thread_telemetry(
        project_name=scenario["project_name"], thread_id=thread_id, run_tag=run_tag
    )
    telemetry_available = (
        telemetry["spans_count"] >= 1.0 or telemetry["traces_count"] >= 1.0
    )
    require_telemetry = (
        os.getenv("OPIK_EXAMPLE_REQUIRE_TELEMETRY", "false").lower() == "true"
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
