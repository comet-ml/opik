import logging
import time
from typing import Any, Callable, Dict, List, Optional, Union, cast

from ..api_objects.prompt import base_prompt
from ..api_objects import opik_client
from ..api_objects import dataset, experiment
from ..api_objects.experiment import helpers as experiment_helpers
from ..api_objects.prompt.chat import chat_prompt_template
from ..api_objects.prompt import types as prompt_types
from . import (
    asyncio_support,
    engine,
    evaluation_result,
    report,
    rest_operations,
    samplers,
)
from .metrics import base_metric, score_result
from .models import ModelCapabilities, base_model, models_factory
from .scorers import scorer_function, scorer_wrapper_metric
from . import test_result
from .types import ExperimentScoreFunction, LLMTask, ScoringKeyMappingType
from .. import url_helpers

LOGGER = logging.getLogger(__name__)
MODALITY_SUPPORT_DOC_URL = (
    "https://www.comet.com/docs/opik/evaluation/evaluate_multimodal"
)


def _try_notifying_about_experiment_completion(
    experiment: experiment.Experiment,
) -> None:
    try:
        experiment.experiments_rest_client.finish_experiments(ids=[experiment.id])
    except Exception:
        LOGGER.debug(
            "Failed to notify backend about the experiment completion. Experiment ID: %s",
            experiment.id,
            exc_info=True,
        )


def _compute_experiment_scores(
    experiment_scoring_functions: List[ExperimentScoreFunction],
    test_results: List[test_result.TestResult],
) -> List[score_result.ScoreResult]:
    """Compute experiment-level scores from test results."""
    if not experiment_scoring_functions or not test_results:
        return []

    all_scores: List[score_result.ScoreResult] = []
    for score_function in experiment_scoring_functions:
        try:
            scores = score_function(test_results)
            # Handle Union[ScoreResult, List[ScoreResult]]
            if isinstance(scores, list):
                all_scores.extend(scores)
            else:
                all_scores.append(scores)
        except Exception as e:
            LOGGER.warning(
                "Failed to compute experiment score: %s",
                e,
                exc_info=True,
            )

    return all_scores


def evaluate(
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset. You can use either `scoring_metrics` or `scorer_functions` to calculate
    evaluation metrics. The scorer functions doesn't require `scoring_key_mapping` and use reserved parameters
    to receive inputs and outputs from the task.

    Args:
        dataset: An Opik dataset instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
            but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
            the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: The name of the experiment associated with evaluation run.
            If None, a generated name will be used.

        project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`

        experiment_config: The dictionary with parameters that describe experiment

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.
            If no value provided, the experiment won't have any scoring metrics.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:
                • dataset_item — a dictionary containing the dataset item content,
                • task_outputs — a dictionary containing the LLM task output.
                • task_span - the data collected during the LLM task execution [optional].

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.
            0 - no outputs, 1 - outputs are enabled (default), 2 - outputs are enabled and detailed statistics
            are displayed.

        nb_samples: number of samples to evaluate. If no value is provided, all samples in the dataset will be evaluated.

        task_threads: number of thread workers to run tasks. If set to 1, no additional
            threads are created, all tasks executed in the current thread sequentially.
            are executed sequentially in the current thread.
            Use more than 1 worker if your task object is compatible with sharing across threads.

        prompt: Prompt object to link with experiment. Deprecated, use `prompts` argument instead.

        prompts: A list of Prompt objects to link with experiment.

        scoring_key_mapping: A dictionary that allows you to rename keys present in either the dataset item or the task output
            so that they match the keys expected by the scoring metrics. For example if you have a dataset item with the following content:
            {"user_question": "What is Opik ?"} and a scoring metric that expects a key "input", you can use scoring_key_mapping
            `{"input": "user_question"}` to map the "user_question" key to "input".

        dataset_item_ids: list of dataset item ids to evaluate. If not provided, all samples in the dataset will be evaluated.

        dataset_sampler: An instance of a dataset sampler that will be used to sample dataset items for evaluation.
            If not provided, all samples in the dataset will be evaluated.

        trial_count: number of times to run the task and evaluate the task output for every dataset item.

        experiment_scoring_functions: List of callable functions that compute experiment-level scores.
            Each function takes a list of TestResult objects and returns a list of ScoreResult objects.
            These scores are computed after all test results are collected and represent aggregate
            metrics across the entire experiment.
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    client = opik_client.get_client_cached()

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    return _evaluate_task(
        client=client,
        experiment=experiment,
        dataset=dataset,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        nb_samples=nb_samples,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
    )


def _evaluate_task(
    *,
    client: opik_client.Opik,
    experiment: experiment.Experiment,
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    project_name: Optional[str],
    verbose: int,
    nb_samples: Optional[int],
    task_threads: int,
    scoring_key_mapping: Optional[ScoringKeyMappingType],
    dataset_item_ids: Optional[List[str]],
    dataset_sampler: Optional[samplers.BaseDatasetSampler],
    trial_count: int,
    experiment_scoring_functions: List[ExperimentScoreFunction],
) -> evaluation_result.EvaluationResult:
    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            scoring_metrics=scoring_metrics,
            workers=task_threads,
            verbose=verbose,
            scoring_key_mapping=scoring_key_mapping,
        )
        test_results = evaluation_engine.evaluate_llm_task_on_dataset(
            dataset_=dataset,
            task=task,
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
            dataset_sampler=dataset_sampler,
            trial_count=trial_count,
            experiment_=experiment,
        )

    total_time = time.time() - start_time

    # Compute experiment scores
    computed_experiment_scores = _compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset.name, total_time, test_results, computed_experiment_scores
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    # Log experiment scores to backend
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=trial_count,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    scoring_threads: int = 16,
    verbose: int = 1,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_id: Optional[str] = None,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
) -> evaluation_result.EvaluationResult:
    """Update the existing experiment with new evaluation metrics. You can use either `scoring_metrics` or `scorer_functions` to calculate
    evaluation metrics. The scorer functions doesn't require `scoring_key_mapping` and use reserved parameters
    to receive inputs and outputs from the task.

    Args:
        experiment_name: The name of the experiment to update.

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:
                • dataset_item — a dictionary containing the dataset item content,
                • task_outputs — a dictionary containing the LLM task output.
                • task_span - the data collected during the LLM task execution [optional].

        scoring_threads: amount of thread workers to run scoring metrics.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.

        scoring_key_mapping: A dictionary that allows you to rename keys present in either the dataset item or the task output
            so that they match the keys expected by the scoring metrics. For example, if you have a dataset item with the following content:
            {"user_question": "What is Opik ?"} and a scoring metric that expects a key "input", you can use scoring_key_mapping
            `{"input": "user_question"}` to map the "user_question" key to "input".

        experiment_id: The ID of the experiment to evaluate. If not provided, the experiment will be evaluated based on the experiment name.

        experiment_scoring_functions: List of callable functions that compute experiment-level scores.
            Each function takes a list of TestResult objects and returns a list of ScoreResult objects.
            These scores are computed after all test results are collected and represent aggregate
            metrics across the entire experiment.
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )
    start_time = time.time()

    client = opik_client.get_client_cached()

    if experiment_id:
        LOGGER.info("Getting experiment by id. Experiment name is ignored.")
        experiment = client.get_experiment_by_id(id=experiment_id)
    else:
        experiment = rest_operations.get_experiment_with_unique_name(
            client=client, experiment_name=experiment_name
        )

    dataset_ = client.get_dataset(name=experiment.dataset_name)

    test_cases = rest_operations.get_experiment_test_cases(
        experiment_=experiment,
        dataset_=dataset_,
        scoring_key_mapping=scoring_key_mapping,
    )
    first_trace_id = test_cases[0].trace_id
    project_name = rest_operations.get_trace_project_name(
        client=client, trace_id=first_trace_id
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            scoring_metrics=scoring_metrics,
            workers=scoring_threads,
            verbose=verbose,
            scoring_key_mapping=scoring_key_mapping,
        )
        test_results = evaluation_engine.evaluate_test_cases(
            test_cases=test_cases,
        )

    total_time = time.time() - start_time

    # Compute experiment scores
    computed_experiment_scores = _compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset_.name,
            total_time,
            test_results,
            computed_experiment_scores,
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset_.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    _try_notifying_about_experiment_completion(experiment)

    # Log experiment scores to backend
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset_.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=1,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset_.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def _build_prompt_evaluation_task(
    model: base_model.OpikBaseModel, messages: List[Dict[str, Any]]
) -> Callable[[Dict[str, Any]], Dict[str, Any]]:
    supported_modalities = cast(
        prompt_types.SupportedModalities,
        {
            "vision": ModelCapabilities.supports_vision(
                getattr(model, "model_name", None)
            ),
            "video": ModelCapabilities.supports_video(
                getattr(model, "model_name", None)
            ),
        },
    )
    # Disable placeholder validation since we pass all dataset item fields to format()
    chat_prompt_template_ = chat_prompt_template.ChatPromptTemplate(
        messages=messages, validate_placeholders=False
    )

    required_modalities = chat_prompt_template_.required_modalities()
    unsupported_modalities = {
        modality
        for modality in required_modalities
        if not supported_modalities.get(modality, False)
    }

    if unsupported_modalities:
        modalities_list = ", ".join(sorted(unsupported_modalities))
        LOGGER.warning(
            "Model '%s' does not support %s content. Multimedia parts will be flattened "
            "to text placeholders. See %s for supported models and customization options.",
            getattr(model, "model_name", "unknown"),
            modalities_list,
            MODALITY_SUPPORT_DOC_URL,
        )

    def _prompt_evaluation_task(prompt_variables: Dict[str, Any]) -> Dict[str, Any]:
        template_type_override = prompt_variables.get("type")
        processed_messages = chat_prompt_template_.format(
            variables=prompt_variables,
            supported_modalities=supported_modalities,
            template_type=template_type_override,
        )

        with base_model.get_provider_response(
            model_provider=model, messages=processed_messages
        ) as llm_output:
            return {
                "input": processed_messages,
                "output": llm_output.choices[0].message.content,
            }

    return _prompt_evaluation_task


def evaluate_prompt(
    dataset: dataset.Dataset,
    messages: List[Dict[str, Any]],
    model: Optional[Union[str, base_model.OpikBaseModel]] = None,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs prompt evaluation on a given dataset.

    Args:
        dataset: An Opik dataset instance

        messages: A list of prompt messages to evaluate.

        model: The name of the model to use for evaluation. Defaults to "gpt-3.5-turbo".

        scoring_metrics: List of metrics to calculate during evaluation.
            The LLM input and output will be passed as arguments to each metric `score(...)` method.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:
                • dataset_item — a dictionary containing the dataset item content,
                • task_outputs — a dictionary containing the LLM task output.
                • task_span - the data collected during the LLM task execution [optional].

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
            but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
            the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: name of the experiment.

        project_name: The name of the project to log data

        experiment_config: configuration of the experiment.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.

        nb_samples: number of samples to evaluate.

        task_threads: amount of thread workers to run scoring metrics.

        prompt: Prompt object to link with experiment.

        dataset_item_ids: list of dataset item ids to evaluate. If not provided, all samples in the dataset will be evaluated.

        dataset_sampler: An instance of a dataset sampler that will be used to sample dataset items for evaluation.
            If not provided, all samples in the dataset will be evaluated.

        trial_count: number of times to execute the prompt and evaluate the LLM output for every dataset item.

        experiment_scoring_functions: List of callable functions that compute experiment-level scores.
            Each function takes a list of TestResult objects and returns a list of ScoreResult objects.
            These scores are computed after all test results are collected and represent aggregate
            metrics across the entire experiment.
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )
    if isinstance(model, str):
        opik_model = models_factory.get(model_name=model)
    elif not isinstance(model, base_model.OpikBaseModel):
        raise ValueError("`model` must be either a string or an OpikBaseModel instance")
    else:
        opik_model = model

    if experiment_config is None:
        experiment_config = {
            "prompt_template": messages,
            "model": opik_model.model_name,
        }
    else:
        if "prompt_template" not in experiment_config:
            experiment_config["prompt_template"] = messages

        if "model" not in experiment_config:
            experiment_config["model"] = opik_model.model_name

    client = opik_client.get_client_cached()

    prompts = [prompt] if prompt else None

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            scoring_metrics=scoring_metrics,
            workers=task_threads,
            verbose=verbose,
            scoring_key_mapping=None,
        )
        test_results = evaluation_engine.evaluate_llm_task_on_dataset(
            dataset_=dataset,
            task=_build_prompt_evaluation_task(model=opik_model, messages=messages),
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
            dataset_sampler=dataset_sampler,
            trial_count=trial_count,
            experiment_=experiment,
        )

    total_time = time.time() - start_time

    # Compute experiment scores
    computed_experiment_scores = _compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset.name, total_time, test_results, computed_experiment_scores
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    # Log experiment scores to backend
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=trial_count,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def evaluate_optimization_trial(
    optimization_id: str,
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset.

    Args:
        optimization_id: The ID of the optimization associated with the experiment.

        dataset: An Opik dataset instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:
                • dataset_item — a dictionary containing the dataset item content,
                • task_outputs — a dictionary containing the LLM task output.
                • task_span - the data collected during the LLM task execution [optional].

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
                    but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
                    the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: The name of the experiment associated with evaluation run.
            If None, a generated name will be used.

        project_name: The name of the project. If not provided, traces and spans will be logged to the `Default Project`

        experiment_config: The dictionary with parameters that describe experiment

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.
            If no value provided, the experiment won't have any scoring metrics.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.
            0 - no outputs, 1 - outputs are enabled (default).

        nb_samples: number of samples to evaluate. If no value is provided, all samples in the dataset will be evaluated.

        task_threads: number of thread workers to run tasks. If set to 1, no additional
            threads are created, all tasks executed in the current thread sequentially.
            are executed sequentially in the current thread.
            Use more than 1 worker if your task object is compatible with sharing across threads.

        prompt: Prompt object to link with experiment. Deprecated, use `prompts` argument instead.

        prompts: A list of Prompt objects to link with experiment.

        scoring_key_mapping: A dictionary that allows you to rename keys present in either the dataset item or the task output
            so that they match the keys expected by the scoring metrics. For example if you have a dataset item with the following content:
            {"user_question": "What is Opik ?"} and a scoring metric that expects a key "input", you can use scoring_key_mapping
            `{"input": "user_question"}` to map the "user_question" key to "input".

        dataset_item_ids: list of dataset item ids to evaluate. If not provided, all samples in the dataset will be evaluated.

        dataset_sampler: An instance of a dataset sampler that will be used to sample dataset items for evaluation.
            If not provided, all samples in the dataset will be evaluated.

        trial_count: number of times to execute the prompt and evaluate the LLM output for every dataset item.

        experiment_scoring_functions: List of callable functions that compute experiment-level scores.
            Each function takes a list of TestResult objects and returns a list of ScoreResult objects.
            These scores are computed after all test results are collected and represent aggregate
            metrics across the entire experiment.
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    if scoring_metrics is None:
        scoring_metrics = []

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    client = opik_client.get_client_cached()

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
        type="trial",
        optimization_id=optimization_id,
    )

    return _evaluate_task(
        client=client,
        experiment=experiment,
        dataset=dataset,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        nb_samples=nb_samples,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
    )


def evaluate_on_dict_items(
    items: List[Dict[str, Any]],
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    project_name: Optional[str] = None,
    verbose: int = 0,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    scoring_threads: int = 16,
) -> evaluation_result.EvaluationResultOnDictItems:
    """
    Lightweight evaluation function that evaluates a task on dataset items (as dictionaries)
    without requiring a Dataset object or creating an experiment.

    This function is useful for optimization scenarios where you need to evaluate many
    candidate solutions quickly using Opik's metric infrastructure. It creates traces for
    tracking but doesn't require experiment setup or dataset management.

    Args:
        items: List of dataset item contents (dictionaries with the data to evaluate).

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric's `score(...)` method will be called with arguments taken from
            the dataset item and task output.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function accepts predefined arguments:
                • dataset_item — a dictionary containing the dataset item content,
                • task_outputs — a dictionary containing the LLM task output.

        project_name: The name of the project for logging traces.

        verbose: Controls evaluation output logs and progress bars.
            0 - no outputs (default), 1 - enable outputs.

        scoring_key_mapping: A dictionary that allows you to rename keys present in either
            the dataset item or the task output to match the keys expected by scoring metrics.

        scoring_threads: Number of thread workers to run scoring metrics.

    Returns:
        EvaluationResultOnDictItems object containing test results and providing methods
        to aggregate scores, similar to the regular evaluation result.

    Example:
        ```python
        import opik
        from opik.evaluation.metrics import Equals

        items = [
            {"input": "What is 2+2?", "expected_output": "4"},
            {"input": "What is 3+3?", "expected_output": "6"},
        ]

        def my_task(item):
            # Your LLM call here
            question = item["input"]
            # ... call model ...
            return {"output": model_output}

        result = opik.evaluate_on_dict_items(
            items=items,
            task=my_task,
            scoring_metrics=[Equals()],
            scoring_key_mapping={"reference": "expected_output"},
        )

        # Access individual test results
        for test_result in result.test_results:
            print(f"Score: {test_result.score_results[0].value}")

        # Get aggregated statistics
        aggregated = result.aggregate_evaluation_scores()
        print(f"Mean equals score: {aggregated['equals_metric'].mean}")
        ```
    """
    # Wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    if not scoring_metrics:
        LOGGER.warning("No scoring metrics provided for items evaluation")
        return evaluation_result.EvaluationResultOnDictItems(test_results=[])

    client = opik_client.get_client_cached()

    # Create evaluation engine
    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            scoring_metrics=scoring_metrics,
            workers=scoring_threads,
            verbose=verbose,
            scoring_key_mapping=scoring_key_mapping,
        )

        # Use the new evaluate_items method
        test_results = evaluation_engine.evaluate_llm_task_on_dict_items(
            items=items,
            task=task,
        )

    return evaluation_result.EvaluationResultOnDictItems(
        test_results=test_results,
    )


def _wrap_scoring_functions(
    scoring_functions: Optional[List[scorer_function.ScorerFunction]],
    scoring_metrics: Optional[List[base_metric.BaseMetric]],
    project_name: Optional[str],
) -> List[base_metric.BaseMetric]:
    if scoring_functions:
        function_metrics = scorer_wrapper_metric.wrap_scorer_functions(
            scoring_functions, project_name=project_name
        )
        if scoring_metrics:
            scoring_metrics.extend(function_metrics)
        else:
            scoring_metrics = function_metrics

    return scoring_metrics if scoring_metrics else []


def _use_or_create_experiment_name(
    experiment_name: Optional[str], experiment_name_prefix: Optional[str]
) -> Optional[str]:
    if experiment_name:
        return experiment_name

    if experiment_name_prefix:
        return experiment_helpers.generate_unique_experiment_name(
            experiment_name_prefix
        )
    else:
        return None
