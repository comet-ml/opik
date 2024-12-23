import pytest
from guardrails import Guard, OnFailAction
from guardrails.hub import CompetitorCheck, ToxicLanguage

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.guardrails.guardrails_tracker import track_guardrails

from ...testlib import ANY_BUT_NONE, ANY_DICT, SpanModel, TraceModel, assert_equal


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("guardrails-integration-test", "guardrails-integration-test"),
    ],
)
def test_guardrails__trace_and_span_per_one_validation_check(
    fake_backend, ensure_openai_configured, project_name, expected_project_name
):
    competitor_check = CompetitorCheck(
        ["Apple", "Microsoft", "Google"], on_fail=OnFailAction.NOOP
    )
    toxic_check = ToxicLanguage(
        threshold=0.5, validation_method="sentence", on_fail=OnFailAction.NOOP
    )
    guard: Guard = Guard().use_many(competitor_check, toxic_check)
    guard = track_guardrails(guard, project_name=project_name)

    guard.validate(
        "An apple a day keeps a doctor away. This is good advice for keeping your health."
    )  # Both the guardrails pass

    opik.flush_tracker()

    COMPETITOR_CHECK_EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="guardrails/competitor_check.validate",
        input={
            "value": "An apple a day keeps a doctor away. This is good advice for keeping your health.",
            "metadata": ANY_DICT,
        },
        output=ANY_BUT_NONE,
        tags=["guardrails", "pass"],
        metadata={"created_from": "guardrails"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                # type="llm",
                name="guardrails/competitor_check.validate",
                input={
                    "value": "An apple a day keeps a doctor away. This is good advice for keeping your health.",
                    "metadata": ANY_DICT,
                },
                output=ANY_BUT_NONE,
                tags=["guardrails", "pass"],
                metadata={"created_from": "guardrails"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            )
        ],
    )

    TOXIC_CHECK_EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="guardrails/toxic_language.validate",
        input={
            "value": "An apple a day keeps a doctor away. This is good advice for keeping your health.",
            "metadata": ANY_DICT,
        },
        output=ANY_BUT_NONE,
        tags=["guardrails", "pass"],
        metadata={"created_from": "guardrails"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                # type="llm",
                name="guardrails/toxic_language.validate",
                input={
                    "value": "An apple a day keeps a doctor away. This is good advice for keeping your health.",
                    "metadata": ANY_DICT,
                },
                output=ANY_BUT_NONE,
                tags=["guardrails", "pass"],
                metadata={"created_from": "guardrails"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
            )
        ],
    )

    assert_equal(COMPETITOR_CHECK_EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
    assert_equal(TOXIC_CHECK_EXPECTED_TRACE_TREE, fake_backend.trace_trees[1])
