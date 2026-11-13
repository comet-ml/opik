"""A failed candidate-generation round ends gracefully unless the config is broken."""

from typing import Any

import pytest
from litellm.exceptions import AuthenticationError, BadRequestError, NotFoundError

from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import candidate_ops
from opik_optimizer.core.llm_calls import StructuredOutputParsingError

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


def _generate(mock_llm_call: Any, error: Exception, single: bool) -> Any:
    mock_llm_call(raises=error)
    optimizer = MetaPromptOptimizer(verbose=0)
    optimizer.enable_context = False
    prompts = {"a": ChatPrompt(system="s", user="u")}
    if not single:
        prompts["b"] = ChatPrompt(system="s2", user="u2")
    return candidate_ops.generate_round_candidates(
        optimizer=optimizer,
        best_prompts=prompts,
        best_score=0.5,
        round_num=0,
        previous_rounds=[],
        metric=lambda item, output: 0.5,
        prompts_this_round=2,
        build_history_context_fn=lambda _: "",
        get_task_context_fn=lambda **_: ("", ""),
        is_single_prompt_optimization=single,
    )


@pytest.mark.parametrize("single", [True, False])
@pytest.mark.parametrize(
    "error",
    [
        StructuredOutputParsingError("{not json", ValueError("bad")),
        BadRequestError(message="context too long", model="m", llm_provider="p"),
    ],
    ids=["malformed_json", "bad_request"],
)
def test_recoverable_generation_errors_yield_no_candidates(
    mock_llm_call: Any, error: Exception, single: bool
) -> None:
    assert _generate(mock_llm_call, error, single) == []


@pytest.mark.parametrize("single", [True, False])
@pytest.mark.parametrize(
    "error",
    [
        AuthenticationError(message="bad key", llm_provider="p", model="m"),
        NotFoundError(message="no such model", model="m", llm_provider="p"),
        BadRequestError(
            message="LLM Provider NOT provided. You passed model=m",
            model="m",
            llm_provider="",
        ),
    ],
    ids=["auth", "not_found", "unknown_provider"],
)
def test_config_errors_still_raise(
    mock_llm_call: Any, error: Exception, single: bool
) -> None:
    with pytest.raises(type(error)):
        _generate(mock_llm_call, error, single)
