import logging
import time
from typing import (
    Any,
    Callable,
    Dict,
    Iterator,
    List,
    Optional,
    Tuple,
    TYPE_CHECKING,
    Union,
    cast,
)

from opik.types import TraceSource
from ..api_objects.prompt import base_prompt
from ..api_objects import opik_client
from ..api_objects import dataset, experiment
from ..api_objects.dataset import dataset_item
from ..api_objects.experiment import helpers as experiment_helpers
from ..api_objects.dataset import execution_policy as dataset_execution_policy
from ..api_objects.prompt.chat import chat_prompt_template
from ..api_objects.prompt import types as prompt_types
from ..api_objects.dataset import test_suite as test_suite_module
from . import (
    asyncio_support,
    engine,
    evaluation_result,
    helpers,
    report,
    rest_operations,
    samplers,
)
from . import resume as resume_module
from .resume import integration as resume_integration
from .resume import merge as resume_merge
from .metrics import base_metric
from .suite_evaluators.llm_judge import (
    metric as suite_evaluators_llm_judge_metric,
    strategy_selector as suite_evaluators_strategy,
)
from .models import ModelCapabilities, base_model, models_factory
from .scorers import scorer_function, scorer_wrapper_metric
from .types import ExperimentScoreFunction, LLMTask, ScoringKeyMappingType
from .. import url_helpers, exceptions
from ..api_objects.dataset.test_suite import suite_result_constructor

if TYPE_CHECKING:
    from ..api_objects.dataset.test_suite import types as suite_types

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


def _materialize_for_checkpoint(
    *,
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    dataset_item_ids: Optional[List[str]],
    dataset_sampler: Optional[samplers.BaseDatasetSampler],
) -> Tuple[Iterator[dataset_item.DatasetItem], Optional[int], Optional[List[str]]]:
    """
    Resolve the (iterator, total, resolved_ids) tuple for the engine + the
    resume checkpoint, without breaking lazy streaming when streaming is
    possible.

    Three cases:
      * Sampler (with or without explicit ids) → the iterator was already
        built from a materialized list inside ``resolve_dataset_items``;
        we drain it once to surface the post-sampler ids for the
        checkpoint, then hand a fresh iterator over the same list to the
        engine. Sampler precedence ensures the checkpoint reflects what
        the engine actually iterated, not the raw input ids — otherwise a
        resume would replay a different item set than the original eval.
      * Explicit ``dataset_item_ids`` only → ids are known up front; the
        checkpoint gets them directly and the iterator is left untouched
        so the engine can still consume it lazily.
      * Neither → streaming. No checkpoint needed; the iterator is passed
        straight to the engine.
    """
    if dataset_sampler is not None:
        materialized = list(items_iter)
        return (
            iter(materialized),
            len(materialized),
            [item.id for item in materialized],
        )
    if dataset_item_ids is not None:
        return items_iter, total_items, list(dataset_item_ids)
    return items_iter, total_items, None


def evaluate(
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
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
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
    blueprint_id: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset. You can use either `scoring_metrics` or `scorer_functions` to calculate
    evaluation metrics. The scorer functions doesn't require `scoring_key_mapping` and use reserved parameters
    to receive inputs and outputs from the task.

    Args:
        dataset: An Opik Dataset or DatasetVersion instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
            but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
            the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: The name of the experiment associated with evaluation run.
            If None, a generated name will be used.

        project_name: Deprecated. If the dataset has a ``project_name`` set, it
            is always used and this override is ignored (with a warning). If
            the dataset has no ``project_name``, traces and spans are logged to
            this project (or to ``Default Project`` when omitted).

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

            - dataset_item — a dictionary containing the dataset item content,
            - task_outputs — a dictionary containing the LLM task output.
            - task_span - the data collected during the LLM task execution [optional].

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

        experiment_tags: Optional list of tags to associate with the experiment.

        dataset_filter_string: Optional OQL filter string to filter dataset items.
            Supports filtering by tags, data fields, metadata, etc.

            Supported columns include:
            - `id`, `source`, `trace_id`, `span_id`: String fields
            - `data`: Dictionary field (use dot notation, e.g., "data.category")
            - `tags`: List field (use "contains" operator)
            - `created_at`, `last_updated_at`: DateTime fields (ISO 8601 format)
            - `created_by`, `last_updated_by`: String fields

            Examples:
            - `tags contains "failed"` - Items with 'failed' tag
            - `data.category = "test"` - Items with specific data field value
            - `created_at >= "2024-01-01T00:00:00Z"` - Items created after date
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # backwards compatibility for transition period
        dataset = dataset.__internal_api__dataset__

    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    client = opik_client.get_global_client()

    if blueprint_id:
        experiment_config = helpers.merge_blueprint_into_config(
            client,
            blueprint_id,
            experiment_config,
        )

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate",
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
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
        items_iter=items_iter,
        total_items=total_items,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
        source="experiment",
    )


def __internal_api__run_test_suite__(
    suite_dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    task: LLMTask,
    *,
    client: Optional[opik_client.Opik],
    dataset_item_ids: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    experiment_tags: Optional[List[str]] = None,
    verbose: int = 2,
    task_threads: int = 16,
    evaluator_model: Optional[str] = None,
    optimization_id: Optional[str] = None,
    experiment_type: Optional[str] = None,
    generate_report: bool = True,
    report_output_path: Optional[str] = None,
    blueprint_id: Optional[str] = None,
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> "suite_types.TestSuiteResult":
    """
    Internal function that runs the full test suite evaluation pipeline:
    task validation, evaluation, report generation, and result display.

    Used by both ``run_tests()`` and
    ``TestSuite.__internal_api__run_optimization_suite__()``.
    """
    from ..api_objects.dataset.test_suite.test_suite import validate_task_result
    from ..api_objects.dataset.test_suite.report_processors import (
        displayer,
        file_writer,
    )

    import functools

    if client is None:
        client = opik_client.get_global_client()

    if blueprint_id:
        experiment_config = helpers.merge_blueprint_into_config(
            client,
            blueprint_id,
            experiment_config,
        )

    @functools.wraps(task)
    def _validated_task(data: Dict[str, Any]) -> Any:
        return validate_task_result(task(data), input_data=data)

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    # NOTE: test-suite experiments do not currently support evaluate_resume,
    # so we deliberately do not embed resume state on them. The persistence
    # primitives in opik.evaluation.resume.state are designed so a test-suite
    # entrypoint can be added later without changing the schema.

    create_experiment_kwargs: Dict[str, Any] = dict(
        name=experiment_name,
        dataset_name=suite_dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
        # TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
        evaluation_method="evaluation_suite",
        tags=experiment_tags,
        dataset_version_id=None,
        project_name=project_name,
    )
    source = "experiment"
    if optimization_id is not None:
        create_experiment_kwargs["type"] = experiment_type or "trial"
        create_experiment_kwargs["optimization_id"] = optimization_id
        source = "optimization"

    experiment_ = client.create_experiment(**create_experiment_kwargs)

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=suite_dataset,
        nb_samples=None,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=None,
        dataset_filter_string=dataset_filter_string,
    )

    if verbose >= 1:
        experiment_url = url_helpers.get_experiment_url_by_id(
            experiment_id=experiment_.id,
            dataset_id=suite_dataset.id,
            base_url=client.config.url_override,
            workspace=client._dereferenced_workspace(),
        )
        report.display_evaluation_in_progress(experiment_url)

    eval_result, total_time = _evaluate_test_suite_task(
        client=client,
        experiment=experiment_,
        dataset=suite_dataset,
        items_iter=items_iter,
        total_items=total_items,
        task=_validated_task,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        evaluator_model=evaluator_model,
        source=source,  # type: ignore[arg-type]
        scoring_tool_strategy=scoring_tool_strategy,
    )

    suite_result = suite_result_constructor.build_suite_result(
        eval_result,
        suite_name=suite_dataset.name,
        total_time=total_time,
    )

    report_path: Optional[str] = None
    if generate_report:
        try:
            report_path = file_writer.save_report(
                suite_result,
                output_path=report_output_path,
            )
        except Exception:
            logging.getLogger(__name__).warning(
                "Failed to save test suite report file.",
                exc_info=True,
            )

    if verbose >= 1:
        displayer.display_suite_results(
            suite_result,
            verbose=verbose,
            report_path=report_path,
        )

    return suite_result


def run_tests(
    test_suite: Union[test_suite_module.TestSuite, test_suite_module.TestSuiteVersion],
    task: LLMTask,
    *,
    experiment_name: Optional[str] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    experiment_tags: Optional[List[str]] = None,
    verbose: int = 2,
    worker_threads: int = 16,
    model: Optional[str] = None,
    generate_report: bool = True,
    report_output_path: Optional[str] = None,
    blueprint_id: Optional[str] = None,
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> "suite_types.TestSuiteResult":
    """
    Run a test suite against a task function.

    Accepts either a :class:`TestSuite` (runs against the latest version) or
    a :class:`TestSuiteVersion` (runs against a specific version snapshot).

    The task function receives each test item's data dict and must return
    either a dict (with ``"input"`` and ``"output"`` keys) or any other
    value, which will be automatically wrapped as
    ``{"input": <item data>, "output": <returned value>}``.

    Args:
        test_suite: The test suite or test suite version to run.
        task: A callable that takes a dict and returns a result.
        experiment_name: Optional explicit name for the experiment.
        experiment_name_prefix: Optional prefix for auto-generated name.
        experiment_config: Optional configuration dict for the experiment.
        prompts: Optional list of Prompt objects to associate.
        experiment_tags: Optional list of tags for the experiment.
        verbose: Verbosity level. 0=silent, 1=summary, 2=detailed (default).
        worker_threads: Number of threads for parallel task execution.
        model: Optional model name for checking assertions.
        generate_report: Whether to generate a JSON report file.
        report_output_path: Optional file path for the report.
        scoring_tool_strategy: Optional override applied to every LLMJudge
            evaluator in the suite. One of ``"auto"`` (size+capability
            heuristic), ``"always"`` (force agentic tool loop) or
            ``"never"`` (force one-shot). When ``None``, each judge's own
            configured strategy is used.

    Returns:
        TestSuiteResult with pass/fail status based on execution policy.

    Example:
        >>> import opik
        >>> result = opik.run_tests(
        ...     test_suite=suite,
        ...     task=my_llm_function,
        ...     experiment_name="v2-prompt-test",
        ... )
        >>> print(f"Pass rate: {result.pass_rate:.0%}")
    """
    suite_dataset: Union[dataset.Dataset, dataset.DatasetVersion]
    if isinstance(test_suite, test_suite_module.TestSuiteVersion):
        suite_dataset = test_suite.__internal_api__dataset_version__
    else:
        suite_dataset = test_suite.__internal_api__dataset__
    client = suite_dataset.client

    return __internal_api__run_test_suite__(
        suite_dataset=suite_dataset,
        task=task,
        client=client,
        experiment_name_prefix=experiment_name_prefix,
        experiment_name=experiment_name,
        project_name=test_suite.project_name,
        experiment_config=experiment_config,
        prompts=prompts,
        experiment_tags=experiment_tags,
        verbose=verbose,
        task_threads=worker_threads,
        evaluator_model=model,
        generate_report=generate_report,
        report_output_path=report_output_path,
        blueprint_id=blueprint_id,
        scoring_tool_strategy=scoring_tool_strategy,
    )


def _evaluate_task(
    *,
    client: opik_client.Opik,
    experiment: experiment.Experiment,
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    project_name: Optional[str],
    verbose: int,
    task_threads: int,
    scoring_key_mapping: Optional[ScoringKeyMappingType],
    trial_count: int,
    experiment_scoring_functions: List[ExperimentScoreFunction],
    source: TraceSource,
) -> evaluation_result.EvaluationResult:
    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=trial_count,
            pass_threshold=trial_count,
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=task_threads,
            verbose=verbose,
            source=source,
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=items_iter,
            task=task,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            experiment_=experiment,
            default_execution_policy=policy,
            total_items=total_items,
        )

    total_time = time.time() - start_time

    # Compute experiment scores
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
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
        base_url=client.config.url_override,
        workspace=client._dereferenced_workspace(),
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


def _apply_scoring_tool_strategy_override(
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_tool_strategy: suite_evaluators_strategy.ScoringToolStrategyMode,
) -> None:
    """Replace each LLMJudge's strategy selector with the suite-level override.

    Walks the resolved evaluator list once; non-LLMJudge metrics are left
    alone. Done after `dataset.get_evaluators(...)` returns so users keep
    the precedence: explicit `run_tests(scoring_tool_strategy=...)` wins over
    each judge's per-instance configuration.
    """
    for metric in scoring_metrics:
        if isinstance(metric, suite_evaluators_llm_judge_metric.LLMJudge):
            metric.set_scoring_tool_strategy(scoring_tool_strategy)


def _evaluate_test_suite_task(
    *,
    client: opik_client.Opik,
    experiment: experiment.Experiment,
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    task: LLMTask,
    project_name: Optional[str],
    verbose: int,
    task_threads: int,
    source: TraceSource,
    evaluator_model: Optional[str],
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> Tuple[evaluation_result.EvaluationResult, float]:
    from opik.message_processing.processors import message_processors_chain

    start_time = time.time()

    # Activate the local emulator so suite-level LLMJudge assertions get
    # access to the full trace tree via the agentic tool loop. The emulator
    # caches every trace/span logged in-process; it stays inactive at idle.
    # Activation is ref-counted (acquire here, release in `finally`), so when
    # this connection's processing chain is shared by several concurrent
    # evaluate() runs the emulator stays active until the last one finishes.
    # `getattr` with a default keeps this MagicMock-friendly:
    # MagicMock auto-rejects attribute names that look like dunders
    # (start and end with `__`), so plain attribute access raises
    # AttributeError on mocked clients used by unit tests. Production
    # clients always have this attribute, so the default never fires.
    chain = getattr(client, "__internal_api__message_processor__", None)
    emulator = (
        message_processors_chain.get_local_emulator_message_processor(chain)
        if chain is not None
        else None
    )
    if chain is not None and emulator is not None:
        # Ref-counted activation: concurrent evaluate() runs that share this
        # connection's processing chain each acquire/release, so the emulator
        # stays active until the last run finishes (instead of the first to
        # exit deactivating it and starving the others' agentic judges).
        message_processors_chain.toggle_local_emulator_message_processor(
            active=True, chain=chain, reset=True
        )

    try:
        with asyncio_support.async_http_connections_expire_immediately():
            scoring_metrics = dataset.get_evaluators(evaluator_model)
            if scoring_tool_strategy is not None:
                _apply_scoring_tool_strategy_override(
                    scoring_metrics, scoring_tool_strategy
                )
            execution_policy = dataset.get_execution_policy()

            evaluation_engine = engine.EvaluationEngine(
                client=client,
                project_name=project_name,
                workers=task_threads,
                verbose=verbose,
                source=source,
            )
            test_results = evaluation_engine.run_and_score(
                dataset_items=items_iter,
                task=task,
                scoring_metrics=scoring_metrics,
                scoring_key_mapping=None,
                evaluator_model=evaluator_model,
                experiment_=experiment,
                default_execution_policy=execution_policy,
                total_items=total_items,
                show_scores_in_progress_bar=False,
            )
    finally:
        if chain is not None and emulator is not None:
            message_processors_chain.toggle_local_emulator_message_processor(
                active=False, chain=chain, reset=True
            )

    total_time = time.time() - start_time

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        base_url=client.config.url_override,
        workspace=client._dereferenced_workspace(),
    )

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=1,
        experiment_scores=[],
    )

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    return evaluation_result_, total_time


def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    scoring_threads: int = 16,
    verbose: int = 1,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_id: Optional[str] = None,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    project_name: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """Update the existing experiment with new evaluation metrics. You can use either `scoring_metrics` or `scorer_functions` to calculate
    evaluation metrics. The scorer functions doesn't require `scoring_key_mapping` and use reserved parameters
    to receive inputs and outputs from the task. The experiment requires at least one test case.

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

            - dataset_item — a dictionary containing the dataset item content,
            - task_outputs — a dictionary containing the LLM task output.
            - task_span - the data collected during the LLM task execution [optional].

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

        project_name: The name of the project to which the experiment belongs. If not provided, the default project will be used.
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )
    start_time = time.time()

    client = opik_client.get_global_client()

    if experiment_id:
        LOGGER.info("Getting experiment by id. Experiment name is ignored.")
        experiment = client.get_experiment_by_id(id=experiment_id)
    else:
        experiment = rest_operations.get_experiment_with_unique_name(
            client=client, experiment_name=experiment_name, project_name=project_name
        )

    dataset_ = client.get_dataset(
        name=experiment.dataset_name, project_name=project_name
    )

    test_cases = rest_operations.get_experiment_test_cases(
        experiment_=experiment,
        dataset_=dataset_,
        scoring_key_mapping=scoring_key_mapping,
    )
    if not test_cases:
        raise exceptions.EmptyExperiment(
            f"Experiment {experiment.id} does not have any test traces to run an evaluation"
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
            workers=scoring_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.score_test_cases(
            test_cases=test_cases,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
        )

    total_time = time.time() - start_time

    client.flush()

    # Compute experiment scores
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
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
        base_url=client.config.url_override,
        workspace=client._dereferenced_workspace(),
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
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
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
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs prompt evaluation on a given dataset.

    Args:
        dataset: An Opik Dataset or DatasetVersion instance

        messages: A list of prompt messages to evaluate.

        model: The name of the model to use for evaluation. Defaults to "gpt-3.5-turbo".

        scoring_metrics: List of metrics to calculate during evaluation.
            The LLM input and output will be passed as arguments to each metric `score(...)` method.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:

            - dataset_item — a dictionary containing the dataset item content,
            - task_outputs — a dictionary containing the LLM task output.
            - task_span - the data collected during the LLM task execution [optional].

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
            but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
            the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: name of the experiment.

        project_name: Deprecated. If the dataset has a ``project_name`` set, it
            is always used and this override is ignored (with a warning). If
            the dataset has no ``project_name``, traces and spans are logged to
            this project (or to ``Default Project`` when omitted).

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

        experiment_tags: List of tags to be associated with the experiment.

        dataset_filter_string: Optional OQL filter string to filter dataset items.
            Supports filtering by tags, data fields, metadata, etc.

            Supported columns include:
            - `id`, `source`, `trace_id`, `span_id`: String fields
            - `data`: Dictionary field (use dot notation, e.g., "data.category")
            - `tags`: List field (use "contains" operator)
            - `created_at`, `last_updated_at`: DateTime fields (ISO 8601 format)
            - `created_by`, `last_updated_by`: String fields

            Examples:
            - `tags contains "failed"` - Items with 'failed' tag
            - `data.category = "test"` - Items with specific data field value
            - `created_at >= "2024-01-01T00:00:00Z"` - Items created after date
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # backwards compatibility for transition period
        dataset = dataset.__internal_api__dataset__

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

    client = opik_client.get_global_client()

    prompts = [prompt] if prompt else None

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate_prompt",
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=trial_count,
            pass_threshold=trial_count,
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=task_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=items_iter,
            task=_build_prompt_evaluation_task(model=opik_model, messages=messages),
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=None,
            evaluator_model=None,
            experiment_=experiment,
            default_execution_policy=policy,
            total_items=total_items,
        )

    total_time = time.time() - start_time

    # Compute experiment scores
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
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
        base_url=client.config.url_override,
        workspace=client._dereferenced_workspace(),
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
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
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
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    Performs task evaluation on a given dataset.

    Args:
        optimization_id: The ID of the optimization associated with the experiment.

        dataset: An Opik Dataset or DatasetVersion instance

        task: A callable object that takes dict with dataset item content
            as input and returns dict which will later be used for scoring.

        scoring_functions: List of scorer functions to be executed during evaluation.
            Each scorer function includes a scoring method that accepts predefined
            arguments supplied by the evaluation engine:

            - dataset_item — a dictionary containing the dataset item content,
            - task_outputs — a dictionary containing the LLM task output.
            - task_span - the data collected during the LLM task execution [optional].

        experiment_name_prefix: The prefix to be added to automatically generated experiment names to make them unique
                    but grouped under the same prefix. For example, if you set `experiment_name_prefix="my-experiment"`,
                    the first experiment created will be named `my-experiment-<unique-random-part>`.

        experiment_name: The name of the experiment associated with evaluation run.
            If None, a generated name will be used.

        project_name: Deprecated. If the dataset has a ``project_name`` set, it
            is always used and this override is ignored (with a warning). If
            the dataset has no ``project_name``, traces and spans are logged to
            this project (or to ``Default Project`` when omitted).

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

        experiment_tags: A list of tags to associate with the experiment.

        dataset_filter_string: Optional OQL filter string to filter dataset items.
            Supports filtering by tags, data fields, metadata, etc.

            Supported columns include:
            - `id`, `source`, `trace_id`, `span_id`: String fields
            - `data`: Dictionary field (use dot notation, e.g., "data.category")
            - `tags`: List field (use "contains" operator)
            - `created_at`, `last_updated_at`: DateTime fields (ISO 8601 format)
            - `created_by`, `last_updated_by`: String fields

            Examples:
            - `tags contains "failed"` - Items with 'failed' tag
            - `data.category = "test"` - Items with specific data field value
            - `created_at >= "2024-01-01T00:00:00Z"` - Items created after date
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # backwards compatibility for transition period
        dataset = dataset.__internal_api__dataset__

    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    if scoring_metrics is None:
        scoring_metrics = []

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate_optimization_trial",
    )

    # wrap scoring functions if any
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    client = opik_client.get_global_client()

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
        type="trial",
        optimization_id=optimization_id,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
    )

    return _evaluate_task(
        client=client,
        experiment=experiment,
        dataset=dataset,
        items_iter=items_iter,
        total_items=total_items,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
        source="optimization",
    )


def evaluate_resume(
    experiment_id: str,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    *,
    verbose: int = 1,
    task_threads: int = 16,
) -> evaluation_result.EvaluationResult:
    """
    Resume an interrupted ``evaluate()`` run.

    Reads the resume state embedded in the existing experiment (dataset
    version, default trial count, dataset filter string, nb_samples) and the
    local checkpoint of resolved item ids when one was written (sampler or
    explicit ``dataset_item_ids`` cases). Items that already have the expected
    number of completed runs are skipped; items with fewer than expected get
    the remaining trials executed.

    The ``task`` and all scoring callables/mappings must be re-supplied —
    Python callables cannot be persisted on the backend, and the
    ``scoring_key_mapping`` is metric-aware (it names the keys specific
    metrics expect), so it travels with those metrics.

    The returned ``EvaluationResult`` describes the **full experiment**, not
    just this call's slice: ``test_results`` is the union of the freshly
    executed runs and reconstructed TestResults for items completed by
    prior runs (using the feedback scores they already had stored).
    ``experiment_scoring_functions`` run over that union, so aggregate
    scores match what a fresh full ``evaluate()`` would have produced.

    Args:
        experiment_id: The id of the experiment to resume.
        task: The callable to run for each pending dataset item. Must match
            the one used in the original run; the framework does not enforce
            consistency.
        scoring_metrics: Per-item scoring metrics. Applied to runs executed
            by this resume call. Previously-completed items keep their
            original stored scores (no re-scoring).
        scoring_functions: Per-item scoring functions, wrapped into metrics
            internally. Same semantics as ``scoring_metrics``.
        scoring_key_mapping: Dict renaming dataset/task-output keys so they
            match the keys ``scoring_metrics`` expect. Must be consistent
            with the metrics passed; the framework does not enforce
            consistency with the original run.
        experiment_scoring_functions: Aggregate scoring callables that take
            the list of ``TestResult`` objects and return ``ScoreResult``\\ s.
            Computed over the merged set (previously-completed + freshly
            executed), so aggregate scores reflect the full experiment.
        verbose: Verbosity level (0 silent, 1 default, 2 detailed stats).
        task_threads: Number of worker threads for task execution.

    Returns:
        ``EvaluationResult`` representing the full experiment after this
        resume call. ``test_results`` spans both reconstructed prior items
        and items executed by this call.

    Raises:
        opik.exceptions.ExperimentNotFound: when the experiment does not exist.
        ExperimentNotResumable: when the experiment was created with a
            configuration that prevents safe resume (e.g. an older SDK
            version that did not embed resume state).
        LocalCheckpointMissing: when the experiment requires a local
            checkpoint of resolved ids and the checkpoint file is not on this
            machine. Resume from the original machine that wrote the
            checkpoint, or re-supply the original ``dataset_item_ids`` via a
            fresh ``evaluate()`` call.
    """
    experiment_scoring_functions = experiment_scoring_functions or []

    client = opik_client.get_global_client()
    context = resume_module.prepare_resume_context(client, experiment_id)

    items = _resolve_resume_items(context)
    pending = list(resume_module.build_pending_items_iterator(iter(items), context))

    if not pending:
        LOGGER.info(
            "Experiment %s is already fully evaluated; nothing to do.",
            experiment_id,
        )

    project_name = context.experiment.project_name
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    # Snapshot already-completed runs **before** ``_evaluate_task`` starts
    # writing new experiment items, otherwise the resume call's own fresh
    # trials would be double-counted in the merged result.
    previous_test_results = resume_merge.reconstruct_previous_test_results(
        experiment=context.experiment,
        dataset_=context.dataset,
    )

    new_result = _evaluate_task(
        client=client,
        experiment=context.experiment,
        dataset=context.dataset,
        items_iter=iter(pending),
        total_items=len(pending),
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=context.default_runs_per_item,
        experiment_scoring_functions=experiment_scoring_functions,
        source="experiment",
    )

    merged = evaluation_result.merge_resume_results(
        new_result=new_result,
        previous_test_results=previous_test_results,
    )

    # ``_evaluate_task`` already logged ``experiment_scoring_functions``
    # over the freshly-replayed slice. Recompute over the merged set and
    # overwrite — the final write reflects the whole experiment, which
    # is what the user (and downstream readers) actually want. We're OK
    # with a brief slice-only window on the backend between the two
    # writes; rate-limit / concurrent-read risk is negligible here.
    merged_scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=merged.test_results,
    )
    if merged_scores:
        context.experiment.log_experiment_scores(score_results=merged_scores)
    merged.experiment_scores = merged_scores

    return merged


def _resolve_resume_items(
    context: "resume_module.ResumeContext",
) -> List[dataset_item.DatasetItem]:
    """
    Resolve the candidate item set for a resume run.

    When a local checkpoint pinned the resolved ids, use it as-is (the
    sampler/explicit-ids decision was locked in by the original call).
    Otherwise iterate via the original ``dataset_filter_string`` and
    ``nb_samples``, against the version-pinned dataset.
    """
    if context.candidate_dataset_item_ids is not None:
        items_iter, _ = helpers.resolve_dataset_items(
            dataset_=context.dataset,
            nb_samples=None,
            dataset_item_ids=context.candidate_dataset_item_ids,
            dataset_sampler=None,
            dataset_filter_string=None,
        )
        return list(items_iter)
    items_iter, _ = helpers.resolve_dataset_items(
        dataset_=context.dataset,
        nb_samples=context.nb_samples,
        dataset_item_ids=None,
        dataset_sampler=None,
        dataset_filter_string=context.dataset_filter_string,
    )
    return list(items_iter)


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

            - dataset_item — a dictionary containing the dataset item content,
            - task_outputs — a dictionary containing the LLM task output.

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

    client = opik_client.get_global_client()

    with asyncio_support.async_http_connections_expire_immediately():
        dataset_items = [
            dataset_item.DatasetItem(id=f"temp_item_{i}", **item)
            for i, item in enumerate(items)
        ]
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=1, pass_threshold=1
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=scoring_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=iter(dataset_items),
            task=task,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            experiment_=None,
            default_execution_policy=policy,
            total_items=len(dataset_items),
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
