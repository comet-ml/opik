"""E2E tests for the agentic LLM-judge path on test suites.

The one-shot LLMJudge sees only the dataset item's top-level `input` /
`output`. The agentic judge — auto-engaged when the local emulator is
active during `opik.run_tests` — receives a pre-rendered flat overview
of the trace plus `read` / `scan` / `search` tools for drilling in.
These tests craft assertions that are decidable ONLY from the
intermediate span state, so a passing verdict is direct evidence the
agentic judge ran:

1. A span-structure assertion ("the agent called the `fetch_user_data`
   helper") — verifiable only by looking at span names in the trace.
2. A span-error assertion ("no errors occurred without errors") on a
   trace whose inner span recorded an error caught by the task —
   verifiable only by looking at span `error_info`.
3. A buried-keyword assertion — the marker sits past the overview's
   floor-tier 500-char truncation, so the judge must call at least one
   of `read` / `scan` / `search` to recover it. The test pins the
   overview sizer's ladder to the floor entry to guarantee truncation
   regardless of the judge model's context budget.

**Judge-model choice.** These tests deliberately override the SDK's
default judge model (`gpt-5-nano`) with a stronger one. `gpt-5-nano`
is the canonical "doesn't engage the tool loop" failure mode the
backend doc (`SupportedJudgeProvider.java`) warns about — it tends to
judge from the inline overview alone, never calling `read`. The SDK
can't prompt-engineer around that; the design doc §9 explicitly
classifies model engagement as the operator's call. Here we pick
`gpt-4o-mini` to match the backend's allow-list second choice — same
OpenAI dependency story, but actually engages with tools.

All require `OPENAI_API_KEY`; the assertions are deliberately
unambiguous to keep judge flakiness low even on a competent judge.
"""

from typing import Any, Dict

import pytest

import opik
from .. import verifiers
from ...testlib import environment, generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)

# Stronger than the SDK default (`gpt-5-nano`) and known to engage the
# tool loop — backend `SupportedJudgeProvider.java` lists it as the
# OpenAI allow-list entry. If you change this, re-run all three tests:
# nano will pass the first two (structural assertions) but reliably
# fail the third (it never calls `read` on truncated content).
AGENTIC_JUDGE_MODEL = "gpt-4o-mini"


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_test_suite_agentic__assertion_about_span_name__passes(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """The agentic judge must inspect the trace's span tree to verify
    that a specifically-named tool span was actually called. The task
    output deliberately omits the helper's name, so a one-shot judge
    has no way to verify the assertion from input/output alone.
    """
    span_assertion = "The agent called a step named `fetch_user_data`"

    @opik.track(name="fetch_user_data", project_name=PROJECT_NAME)
    def fetch_user_data(user_id: str) -> Dict[str, Any]:
        return {"id": user_id, "name": "alice"}

    @opik.track(name="task", project_name=PROJECT_NAME)
    def run_task(item: Dict[str, Any]) -> Dict[str, Any]:
        fetch_user_data("u-1")
        # Output deliberately doesn't mention `fetch_user_data` — the
        # only evidence the helper was called lives in the span tree.
        return {"input": item["input"], "output": "ok"}

    suite = opik_client.create_test_suite(
        name=dataset_name,
        description="Agentic judge — span-name assertion",
        project_name=PROJECT_NAME,
    )
    suite.insert(
        [
            {
                "data": {"input": {"question": "fetch user u-1"}},
                "assertions": [span_assertion],
            }
        ]
    )

    suite_result = opik.run_tests(
        test_suite=suite,
        task=run_task,
        experiment_name=experiment_name,
        verbose=0,
        model=AGENTIC_JUDGE_MODEL,
    )

    # The one assertion must surface in the feedback scores.
    verifiers.verify_test_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=1,
        experiment_items_count=1,
        total_feedback_scores=1,
        expected_score_names={span_assertion},
        project_name=PROJECT_NAME,
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_test_suite_agentic__assertion_about_span_error__detects_failure(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """The agentic judge must inspect `error_info` on a nested span to
    fail an assertion that claims no errors occurred. The task catches
    the exception so the trace's top-level output looks healthy — a
    one-shot judge would incorrectly pass the assertion.
    """
    no_errors_assertion = "Execution completed without errors in any internal step"

    @opik.track(name="risky_step", project_name=PROJECT_NAME)
    def risky_step() -> str:
        # @opik.track captures `error_info` on the inner span when this
        # raises, even though the caller swallows the exception.
        raise RuntimeError("internal failure")

    @opik.track(name="task", project_name=PROJECT_NAME)
    def run_task(item: Dict[str, Any]) -> Dict[str, Any]:
        try:
            risky_step()
        except RuntimeError:
            pass
        return {"input": item["input"], "output": "completed"}

    suite = opik_client.create_test_suite(
        name=dataset_name,
        description="Agentic judge — span-error assertion",
        project_name=PROJECT_NAME,
    )
    suite.insert(
        [
            {
                "data": {"input": {"question": "do the thing"}},
                "assertions": [no_errors_assertion],
            }
        ]
    )

    suite_result = opik.run_tests(
        test_suite=suite,
        task=run_task,
        experiment_name=experiment_name,
        verbose=0,
        model=AGENTIC_JUDGE_MODEL,
    )

    # The single assertion is false (an inner step errored), so the
    # item must fail — proving the agentic judge saw the span's
    # `error_info`. A one-shot judge would see only `output="completed"`
    # and pass the assertion.
    verifiers.verify_test_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=0,
        experiment_items_count=1,
        total_feedback_scores=1,
        expected_score_names={no_errors_assertion},
        project_name=PROJECT_NAME,
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_test_suite_agentic__assertion_requires_buried_keyword_lookup__passes(
    opik_client: opik.Opik,
    dataset_name: str,
    experiment_name: str,
    monkeypatch: pytest.MonkeyPatch,
):
    """The marker is placed past the floor-tier truncation the overview
    applies to every span's I/O. The overview alone physically cannot
    include it, so the judge MUST reach for at least one of
    `read` / `scan` / `search` to find the marker and pass the
    assertion. We don't pin which tool the model picks — that's a
    model-quality question. What we prove here is that the agentic loop
    engages beyond the overview, since a one-shot judge would lack the
    evidence.

    The marker is a synthetic token (`MARKER-` + opaque tail) chosen so
    it can't appear anywhere else in the trace or in the model's
    pretraining. A `score: true` verdict here means the judge actually
    extracted it from span content.

    NOTE on the ladder monkeypatch: the overview sizer normally picks
    the largest per-field limit that fits the model's context budget,
    which on `gpt-4o-mini` (128k window) would render this small trace
    at the `NO_OVERVIEW_TRUNCATION` tier — the marker would be visible
    inline and the test premise would silently regress. Forcing the
    ladder to its single floor entry (`OVERVIEW_IO_FLOOR_CHAR_LIMIT`) keeps
    the truncation guaranteed without needing to produce a multi-MB
    trace just to overflow the budget. The sizer's own behavior is
    covered by `test_overview_sizer.py`.
    """
    from opik.evaluation.suite_evaluators.agentic.compression import (
        span_tree_serializer,
    )

    monkeypatch.setattr(
        span_tree_serializer,
        "OVERVIEW_IO_LIMIT_LADDER",
        (span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT,),
    )

    secret_marker = "MARKER-7f3a8c2e9b1d4f60"
    keyword_assertion = (
        f"At least one intermediate step processed a payload containing "
        f"the literal token '{secret_marker}'"
    )

    @opik.track(name="process_step", project_name=PROJECT_NAME)
    def process_step(payload: str) -> str:
        # The output deliberately doesn't echo the marker — it only
        # references the payload length, so the agent can't shortcut
        # through `output` alone. The marker lives only in the input,
        # which is what the overview truncates at the floor tier.
        return f"step processed {len(payload)} chars"

    @opik.track(name="noop_step", project_name=PROJECT_NAME)
    def noop_step() -> str:
        return "noop"

    @opik.track(name="task", project_name=PROJECT_NAME)
    def run_task(item: Dict[str, Any]) -> Dict[str, Any]:
        # Pad with filler so the marker lands well past the overview's
        # OVERVIEW_IO_FLOOR_CHAR_LIMIT (500 chars) in the JSON-rendered span
        # input `{"payload": "<filler><marker>"}`. `padding ` is 8 chars
        # × 70 = 560 chars; with the ~13-char JSON prefix the marker
        # starts past offset 573, so it sits beyond the overview cap by
        # construction (the ladder is pinned to the floor by the
        # monkeypatch above). Mixed-in noop_step guarantees more than
        # one span exists, so the judge can't trivially infer "must be
        # the only span."
        filler = "padding " * 170
        process_step(payload=f"{filler}{secret_marker} trailer")
        noop_step()
        return {"input": item["input"], "output": "completed"}

    suite = opik_client.create_test_suite(
        name=dataset_name,
        description="Agentic judge — buried-keyword requires read/scan/search",
        project_name=PROJECT_NAME,
    )
    suite.insert(
        [
            {
                "data": {"input": {"question": "process the payload"}},
                "assertions": [keyword_assertion],
            }
        ]
    )

    suite_result = opik.run_tests(
        test_suite=suite,
        task=run_task,
        experiment_name=experiment_name,
        verbose=0,
        model=AGENTIC_JUDGE_MODEL,
    )

    # If the judge stayed at the overview level, it would have seen the
    # truncated string (no marker) and could only have guessed at the
    # assertion. A passing verdict means it drilled into span content
    # via one of read / scan / search.
    verifiers.verify_test_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=1,
        experiment_items_count=1,
        total_feedback_scores=1,
        expected_score_names={keyword_assertion},
        project_name=PROJECT_NAME,
    )
