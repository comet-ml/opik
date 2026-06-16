"""End-to-end regression test for Optimization Studio.

Drives the studio the same way the job runner does — parse the job-context
`studio_config`, build the metric/optimizer/prompt via the studio factories,
and run it through ``studio.helpers.run_optimization`` — but without the Java
job-creation REST call or the RQ queue. LLM calls go through the backend gateway
using the workspace-stored provider key (no provider key is passed to LiteLLM),
matching how the Studio runs in production.

It verifies that, given a dataset and the prompt from the job context, the
optimization actually runs, and uses the produced traces to confirm the
configured model is the one that ran.

Guards the regressions fixed alongside it:
- the selected model is used for evaluation (not the SDK default gpt-5-nano) —
  under the gateway, the default would resolve to a provider the workspace has
  no key for and the run would fail outright,
- a user-only prompt (no system message) doesn't crash GEPA / no-op hierarchical.

Bound the run via ``OPTIMIZER_MAX_TRIALS`` (set in CI) so it stays short.
"""

import pytest

from opik import synchronization
from opik_optimizer import ChatPrompt

from opik_backend.studio.types import OptimizationConfig
from opik_backend.studio.metrics import MetricFactory
from opik_backend.studio.optimizers import OptimizerFactory
from opik_backend.studio.helpers import run_optimization

pytestmark = pytest.mark.e2e


def _studio_config(model: str, optimizer_type: str, dataset_name: str) -> dict:
    """A job-context config with a single USER message (the regression case).

    The gateway needs an explicit ``stream`` field (LiteLLM omits it by default,
    which trips the backend's Anthropic mapper), mirroring optimizer_runner.
    """
    return {
        "dataset_name": dataset_name,
        "prompt": {
            "messages": [
                {
                    "role": "user",
                    "content": 'Classify the sentiment of this movie review as '
                    'exactly "positive" or "negative": {{text}}',
                }
            ]
        },
        "llm_model": {"model": model, "parameters": {"stream": False}},
        "evaluation": {
            "metrics": [
                {
                    "type": "equals",
                    "parameters": {"reference_key": "label", "case_sensitive": False},
                }
            ]
        },
        "optimizer": {"type": optimizer_type, "parameters": {"seed": 42}},
    }


def _models_in_project(opik_client, project_name: str) -> set[str]:
    spans = opik_client.search_spans(project_name=project_name, max_results=1000)
    return {(span.model or "") for span in spans}


@pytest.mark.parametrize("optimizer_type", ["gepa", "hierarchical_reflective"])
def test_studio_optimization_runs_on_dataset_and_prompt(
    opik_client, project_name, seeded_dataset, studio_gateway, optimizer_type
):
    model = studio_gateway["model"]
    expected_substring = studio_gateway["expected_substring"]

    config = OptimizationConfig.from_dict(
        _studio_config(model, optimizer_type, seeded_dataset.name)
    )
    metric_fn = MetricFactory.build(
        config.metric_type, config.metric_params, config.model
    )
    optimizer = OptimizerFactory.build(
        config.optimizer_type,
        config.model,
        config.model_params,
        config.optimizer_params,
    )
    prompt = ChatPrompt(
        messages=config.prompt_messages,
        model=config.model,
        model_parameters=optimizer.model_parameters,
    )

    # optimization_id=None → optimize_prompt creates its own run record, so we
    # exercise the real studio helper without a Java-created optimization.
    result = run_optimization(
        optimizer=optimizer,
        optimization_id=None,
        prompt=prompt,
        dataset=seeded_dataset,
        metric_fn=metric_fn,
        project_name=project_name,
    )

    assert result is not None, "optimization returned no result"
    assert result.score is not None, "optimization produced no score"
    assert 0.0 <= result.score <= 1.0, f"score {result.score} out of range"

    # Verify via traces that the configured model actually ran. Spans land in
    # ClickHouse with eventual consistency, so poll briefly.
    def _has_expected_model() -> bool:
        return any(
            expected_substring in model_name.lower()
            for model_name in _models_in_project(opik_client, project_name)
        )

    assert synchronization.until(
        _has_expected_model, sleep=1.0, max_try_seconds=30
    ), (
        f"No span used the configured model '{model}'; "
        f"saw {_models_in_project(opik_client, project_name)}"
    )

    # The model-passing regression silently fell back to openai/gpt-5-nano —
    # make sure that default never appears.
    leaked = {
        model_name
        for model_name in _models_in_project(opik_client, project_name)
        if "gpt-5-nano" in model_name
    }
    assert not leaked, f"SDK default model leaked into traces: {leaked}"
