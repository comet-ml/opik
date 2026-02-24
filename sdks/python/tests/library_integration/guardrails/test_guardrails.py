import pytest
from guardrails import Guard, OnFailAction
from guardrails.hub import PolitenessCheck

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
    politeness_check = PolitenessCheck(
        llm_callable="gpt-3.5-turbo", on_fail=OnFailAction.NOOP
    )

    guard: Guard = Guard()
    if hasattr(guard, "use_many"):
        guard = guard.use_many(politeness_check)
    else:
        guard = guard.use(politeness_check)
    guard = track_guardrails(guard, project_name=project_name)

    result = guard.validate(
        "Would you be so kind to pass me a cup of tea?",
    )  # Both the guardrails pass
    expected_result_tag = "pass" if result.validation_passed else "fail"
    opik.flush_tracker()

    COMPETITOR_CHECK_EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="guardrails/politeness_check.validate",
        input={
            "value": "Would you be so kind to pass me a cup of tea?",
            "metadata": ANY_DICT,
        },
        output=ANY_BUT_NONE,
        tags=["guardrails", expected_result_tag],
        metadata={"created_from": "guardrails", "model": "gpt-3.5-turbo"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="guardrails/politeness_check.validate",
                input={
                    "value": "Would you be so kind to pass me a cup of tea?",
                    "metadata": ANY_DICT,
                },
                output=ANY_BUT_NONE,
                tags=["guardrails", expected_result_tag],
                metadata={"created_from": "guardrails", "model": "gpt-3.5-turbo"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                model="gpt-3.5-turbo",
                spans=[],
            )
        ],
    )

    assert_equal(COMPETITOR_CHECK_EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
