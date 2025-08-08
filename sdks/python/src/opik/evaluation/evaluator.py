import logging
import time
from typing import Any, Callable, Dict, List, Optional, Union

from .. import Prompt
from ..api_objects import opik_client
from ..api_objects import dataset, experiment
from ..api_objects.experiment import helpers as experiment_helpers
from ..api_objects.prompt import prompt_template
from . import asyncio_support, engine, evaluation_result, report, rest_operations
from .metrics import base_metric
from .models import base_model, models_factory
from .types import LLMTask, ScoringKeyMappingType

LOGGER = logging.getLogger(__name__)


def evaluate(
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[Prompt] = None,
    prompts: Optional[List[Prompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset.

    Args:
        dataset: An Opik dataset instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

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
    """
    if scoring_metrics is None:
        scoring_metrics = []

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    client = opik_client.get_client_cached()

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
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
) -> evaluation_result.EvaluationResult:
    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            experiment_=experiment,
            scoring_metrics=scoring_metrics,
            workers=task_threads,
            verbose=verbose,
            scoring_key_mapping=scoring_key_mapping,
        )
        test_results = evaluation_engine.evaluate_llm_tasks(
            dataset_=dataset,
            task=task,
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
        )

    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(dataset.name, total_time, test_results)

    report.display_experiment_link(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    client.flush()

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
    )

    return evaluation_result_


def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_threads: int = 16,
    verbose: int = 1,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_id: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """Update existing experiment with new evaluation metrics.

    Args:
        experiment_name: The name of the experiment to update.

        scoring_metrics: List of metrics to calculate during evaluation.
            Each metric has `score(...)` method, arguments for this method
            are taken from the `task` output, check the signature
            of the `score` method in metrics that you need to find out which keys
            are mandatory in `task`-returned dictionary.

        scoring_threads: amount of thread workers to run scoring metrics.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.

        scoring_key_mapping: A dictionary that allows you to rename keys present in either the dataset item or the task output
            so that they match the keys expected by the scoring metrics. For example if you have a dataset item with the following content:
            {"user_question": "What is Opik ?"} and a scoring metric that expects a key "input", you can use scoring_key_mapping
            `{"input": "user_question"}` to map the "user_question" key to "input".
    """
    start_time = time.time()

    client = opik_client.get_client_cached()

    if experiment_id:
        LOGGER.info("Getting experiment by id. Experiment name is ignored.")
        experiment = client.get_experiment_by_id(id=experiment_id)
    else:
        experiment = rest_operations.get_experiment_with_unique_name(
            client=client, experiment_name=experiment_name
        )

    test_cases = rest_operations.get_experiment_test_cases(
        client=client,
        experiment_id=experiment.id,
        dataset_id=experiment.dataset_id,
        scoring_key_mapping=scoring_key_mapping,
    )
    first_trace_id = test_cases[0].trace_id
    project_name = rest_operations.get_trace_project_name(
        client=client, trace_id=first_trace_id
    )

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            experiment_=experiment,
            scoring_metrics=scoring_metrics,
            workers=scoring_threads,
            verbose=verbose,
            scoring_key_mapping=scoring_key_mapping,
        )
        test_results = evaluation_engine.evaluate_test_cases(
            test_cases=test_cases,
        )

    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(
            experiment.dataset_name, total_time, test_results
        )

    report.display_experiment_link(
        dataset_id=experiment.dataset_id,
        experiment_id=experiment.id,
        url_override=client.config.url_override,
    )

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=experiment.dataset_id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
    )

    return evaluation_result_


def _build_prompt_evaluation_task(
    model: base_model.OpikBaseModel, messages: List[Dict[str, Any]]
) -> Callable[[Dict[str, Any]], Dict[str, Any]]:
    def _prompt_evaluation_task(prompt_variables: Dict[str, Any]) -> Dict[str, Any]:
        processed_messages = []
        for message in messages:
            processed_messages.append(
                {
                    "role": message["role"],
                    "content": prompt_template.PromptTemplate(
                        message["content"],
                        validate_placeholders=False,
                        type=prompt_variables.get("type", "mustache"),
                    ).format(**prompt_variables),
                }
            )

        llm_output = model.generate_provider_response(messages=processed_messages)

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
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[Prompt] = None,
    dataset_item_ids: Optional[List[str]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs prompt evaluation on a given dataset.

    Args:
        dataset: An Opik dataset instance

        messages: A list of prompt messages to evaluate.

        model: The name of the model to use for evaluation. Defaults to "gpt-3.5-turbo".

        scoring_metrics: List of metrics to calculate during evaluation.
            The LLM input and output will be passed as arguments to each metric `score(...)` method.

        experiment_name: name of the experiment.

        project_name: The name of the project to log data

        experiment_config: configuration of the experiment.

        verbose: an integer value that controls evaluation output logs such as summary and tqdm progress bar.

        nb_samples: number of samples to evaluate.

        task_threads: amount of thread workers to run scoring metrics.

        prompt: Prompt object to link with experiment.

        dataset_item_ids: list of dataset item ids to evaluate. If not provided, all samples in the dataset will be evaluated.
    """
    if isinstance(model, str):
        model = models_factory.get(model_name=model)
    elif not isinstance(model, base_model.OpikBaseModel):
        raise ValueError("`model` must be either a string or an OpikBaseModel instance")

    if experiment_config is None:
        experiment_config = {"prompt_template": messages, "model": model.model_name}
    else:
        if "prompt_template" not in experiment_config:
            experiment_config["prompt_template"] = messages

        if "model" not in experiment_config:
            experiment_config["model"] = model.model_name

    if scoring_metrics is None:
        scoring_metrics = []

    client = opik_client.get_client_cached()

    prompts = [prompt] if prompt else None

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
    )

    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            experiment_=experiment,
            scoring_metrics=scoring_metrics,
            workers=task_threads,
            verbose=verbose,
            scoring_key_mapping=None,
        )
        test_results = evaluation_engine.evaluate_llm_tasks(
            dataset_=dataset,
            task=_build_prompt_evaluation_task(model=model, messages=messages),
            nb_samples=nb_samples,
            dataset_item_ids=dataset_item_ids,
        )

    total_time = time.time() - start_time

    if verbose == 1:
        report.display_experiment_results(dataset.name, total_time, test_results)

    report.display_experiment_link(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    client.flush()

    evaluation_result_ = evaluation_result.EvaluationResult(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        experiment_name=experiment.name,
        test_results=test_results,
    )

    return evaluation_result_


def evaluate_optimization_trial(
    optimization_id: str,
    dataset: dataset.Dataset,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[Prompt] = None,
    prompts: Optional[List[Prompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset.

    Args:
        optimization_id: The ID of the optimization associated with the experiment.

        dataset: An Opik dataset instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

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
    """
    if scoring_metrics is None:
        scoring_metrics = []

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    client = opik_client.get_client_cached()

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
    )
