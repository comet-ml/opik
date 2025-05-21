import base64
import json
from typing import Any, Dict, Iterable, List, Literal, Optional, Set, Union
from unittest import mock

import opik
from opik import Attachment, Prompt, synchronization
from opik.api_objects.dataset import dataset_item
from opik.rest_api import ExperimentPublic
from opik.rest_api.types import (
    attachment as rest_api_attachment,
    span_public,
    trace_public,
)
from opik.types import ErrorInfoDict, FeedbackScoreDict
from .. import testlib

InputType = Union[
    Dict[str, Any],
    List[Any],
    str,
]

OutputType = InputType


def _try_get__dict__(instance: Any) -> Optional[Dict[str, Any]]:
    if instance is None:
        return None

    return instance.__dict__


def _try_build_set(iterable: Optional[Iterable[Any]]) -> Optional[Set[Any]]:
    if iterable is None:
        return iterable

    return set(iterable)


def verify_trace(
    opik_client: opik.Opik,
    trace_id: str,
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: InputType = mock.ANY,  # type: ignore
    output: Optional[OutputType] = mock.ANY,  # type: ignore
    tags: Union[List[str], Set[str]] = mock.ANY,  # type: ignore
    feedback_scores: List[FeedbackScoreDict] = mock.ANY,  # type: ignore
    project_name: Optional[str] = mock.ANY,  # type: ignore
    error_info: Optional[ErrorInfoDict] = mock.ANY,  # type: ignore
    thread_id: Optional[str] = mock.ANY,  # type: ignore
    guardrails_validations: Optional[List[Dict[str, Any]]] = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (opik_client.get_trace_content(id=trace_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get trace with id {trace_id}.")

    trace = opik_client.get_trace_content(id=trace_id)

    assert trace.name == name, f"{trace.name} != {name}"
    assert trace.input == input, testlib.prepare_difference_report(trace.input, input)
    assert trace.output == output, testlib.prepare_difference_report(
        trace.output, output
    )
    assert trace.metadata == metadata, testlib.prepare_difference_report(
        trace.metadata, metadata
    )

    if tags is not mock.ANY:
        assert _try_build_set(trace.tags) == _try_build_set(
            tags
        ), testlib.prepare_difference_report(trace.tags, tags)

    if error_info is not mock.ANY:
        assert (
            _try_get__dict__(trace.error_info) == error_info
        ), testlib.prepare_difference_report(trace.error_info, error_info)

    assert thread_id == trace.thread_id, f"{trace.thread_id} != {thread_id}"

    if project_name is not mock.ANY:
        trace_project = opik_client.get_project(trace.project_id)
        assert trace_project.name == project_name

    if feedback_scores is not mock.ANY:
        if trace.feedback_scores is None:
            assert (
                feedback_scores is None
            ), f"Expected feedback scores to be None, but got {feedback_scores}"
            return

        actual_feedback_scores = (
            [] if trace.feedback_scores is None else trace.feedback_scores
        )
        assert (
            len(actual_feedback_scores) == len(feedback_scores)
        ), f"Expected amount of trace feedback scores ({len(feedback_scores)}) is not equal to actual amount ({len(actual_feedback_scores)})"

        actual_feedback_scores: List[FeedbackScoreDict] = [
            FeedbackScoreDict(
                category_name=score.category_name,
                id=trace_id,
                name=score.name,
                reason=score.reason,
                value=score.value,
            )
            for score in trace.feedback_scores
        ]

        sorted_actual_feedback_scores = sorted(
            actual_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        sorted_expected_feedback_scores = sorted(
            feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        for actual_score, expected_score in zip(
            sorted_actual_feedback_scores, sorted_expected_feedback_scores
        ):
            testlib.assert_dicts_equal(actual_score, expected_score)

    if guardrails_validations is not mock.ANY:
        if trace.guardrails_validations is None:
            assert (
                guardrails_validations is None
            ), f"Expected guardrails validation to be None, but got {guardrails_validations}"
            return

        actual_guardrails_validations = (
            [] if trace.guardrails_validations is None else trace.guardrails_validations
        )
        assert (
            len(actual_guardrails_validations) == len(guardrails_validations)
        ), f"Expected amount of trace guardrails validation ({len(guardrails_validations)}) is not equal to actual amount ({len(actual_guardrails_validations)})"

        actual_guardrails_validations = [
            guardrail.model_dump() for guardrail in trace.guardrails_validations
        ]

        sorted_actual_guardrails_validations = sorted(
            actual_guardrails_validations, key=lambda item: item["span_id"]
        )
        sorted_expected_guardrails_validations = sorted(
            guardrails_validations, key=lambda item: item["span_id"]
        )
        for actual_guardrail, expected_guardrail in zip(
            sorted_actual_guardrails_validations, sorted_expected_guardrails_validations
        ):
            testlib.assert_dicts_equal(actual_guardrail, expected_guardrail)


def verify_span(
    opik_client: opik.Opik,
    span_id: str,
    trace_id: str,
    parent_span_id: Optional[str],
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: InputType = mock.ANY,  # type: ignore
    output: Optional[OutputType] = mock.ANY,  # type: ignore
    tags: Union[List[str], Set[str]] = mock.ANY,  # type: ignore
    type: str = mock.ANY,  # type: ignore
    feedback_scores: List[FeedbackScoreDict] = mock.ANY,  # type: ignore
    project_name: Optional[str] = mock.ANY,
    model: Optional[str] = mock.ANY,  # type: ignore
    provider: Optional[str] = mock.ANY,  # type: ignore
    error_info: Optional[ErrorInfoDict] = mock.ANY,  # type: ignore
    total_cost: Optional[float] = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (opik_client.get_span_content(id=span_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get span with id {span_id}.")

    span = opik_client.get_span_content(id=span_id)

    assert span.trace_id == trace_id, f"{span.trace_id} != {trace_id}"

    if parent_span_id is None:
        assert span.parent_span_id is None, f"{span.parent_span_id} != {parent_span_id}"
    else:
        assert (
            span.parent_span_id == parent_span_id
        ), f"{span.parent_span_id} != {parent_span_id}"

    assert span.name == name, f"{span.name} != {name}"
    assert span.type == type, f"{span.type} != {type}"

    assert span.input == input, testlib.prepare_difference_report(span.input, input)
    assert span.output == output, testlib.prepare_difference_report(span.output, output)
    assert span.metadata == metadata, testlib.prepare_difference_report(
        span.metadata, metadata
    )

    if tags is not mock.ANY:
        assert _try_build_set(span.tags) == _try_build_set(
            tags
        ), testlib.prepare_difference_report(span.tags, tags)

    if error_info is not mock.ANY:
        assert (
            _try_get__dict__(span.error_info) == error_info
        ), testlib.prepare_difference_report(span.error_info, error_info)

    assert span.model == model, f"{span.model} != {model}"
    assert span.provider == provider, f"{span.provider} != {provider}"
    assert (
        span.total_estimated_cost == total_cost
    ), f"{span.total_estimated_cost} != {total_cost}"

    if project_name is not mock.ANY:
        span_project = opik_client.get_project(span.project_id)
        assert span_project.name == project_name

    if feedback_scores is not mock.ANY:
        if span.feedback_scores is None:
            assert (
                feedback_scores is None
            ), f"Expected feedback scores to be None, but got {feedback_scores}"
            return

        actual_feedback_scores = (
            [] if span.feedback_scores is None else span.feedback_scores
        )
        assert (
            len(actual_feedback_scores) == len(feedback_scores)
        ), f"Expected amount of span feedback scores ({len(feedback_scores)}) is not equal to actual amount ({len(actual_feedback_scores)})"

        actual_feedback_scores: List[FeedbackScoreDict] = [
            FeedbackScoreDict(
                category_name=score.category_name,
                id=span_id,
                name=score.name,
                reason=score.reason,
                value=score.value,
            )
            for score in span.feedback_scores
        ]

        sorted_actual_feedback_scores = sorted(
            actual_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        sorted_expected_feedback_scores = sorted(
            feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        for actual_score, expected_score in zip(
            sorted_actual_feedback_scores, sorted_expected_feedback_scores
        ):
            testlib.assert_dicts_equal(actual_score, expected_score)


def verify_dataset(
    opik_client: opik.Opik,
    name: str,
    description: str = mock.ANY,
    dataset_items: List[dataset_item.DatasetItem] = mock.ANY,
):
    if not synchronization.until(
        lambda: (opik_client.get_dataset(name=name) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get dataset with name {name}.")

    actual_dataset = opik_client.get_dataset(name=name)
    assert actual_dataset.description == description

    actual_dataset_items = actual_dataset.__internal_api__get_items_as_dataclasses__()
    assert (
        len(actual_dataset_items) == len(dataset_items)
    ), f"Amount of actual dataset items ({len(actual_dataset_items)}) is not the same as of expected ones ({len(dataset_items)})"

    actual_dataset_items_dicts = [item.__dict__ for item in actual_dataset_items]
    expected_dataset_items_dicts = [item.__dict__ for item in dataset_items]

    sorted_actual_items = sorted(
        actual_dataset_items_dicts, key=lambda item: json.dumps(item, sort_keys=True)
    )
    sorted_expected_items = sorted(
        expected_dataset_items_dicts, key=lambda item: json.dumps(item, sort_keys=True)
    )

    for actual_item, expected_item in zip(sorted_actual_items, sorted_expected_items):
        testlib.assert_dicts_equal(actual_item, expected_item, ignore_keys=["id"])


def verify_experiment(
    opik_client: opik.Opik,
    id: str,
    experiment_name: str,
    experiment_metadata: Optional[Dict[str, Any]],
    feedback_scores_amount: int,
    traces_amount: int,
    prompts: Optional[List[Prompt]] = None,
):
    rest_client = (
        opik_client._rest_client
    )  # temporary solution until backend prepares proper endpoints

    rest_client.datasets.find_dataset_items_with_experiment_items

    if not synchronization.until(
        lambda: (rest_client.experiments.get_experiment_by_id(id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get experiment with id {id}.")

    experiment_content = rest_client.experiments.get_experiment_by_id(id)

    _verify_experiment_metadata(experiment_content, experiment_metadata)

    assert (
        experiment_content.name == experiment_name
    ), f"{experiment_content.name} != {experiment_name}"

    actual_scores_count = (
        0
        if experiment_content.feedback_scores is None
        else len(experiment_content.feedback_scores)
    )
    assert (
        actual_scores_count == feedback_scores_amount
    ), f"{actual_scores_count} != {feedback_scores_amount}"

    actual_trace_count = (
        0 if experiment_content.trace_count is None else experiment_content.trace_count
    )
    assert (
        actual_trace_count == traces_amount
    ), f"{actual_trace_count} != {traces_amount}"

    _verify_experiment_prompts(experiment_content, prompts)


def verify_attachments(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    attachments: Dict[str, Attachment],
    data_sizes: Dict[str, int],
) -> None:
    if not synchronization.until(
        lambda: (
            _get_trace_or_span(
                opik_client, entity_type=entity_type, entity_id=entity_id
            )
            is not None
        ),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get {entity_type} with id {entity_id}.")

    trace_or_span = _get_trace_or_span(
        opik_client, entity_type=entity_type, entity_id=entity_id
    )
    url_override = opik_client._config.url_override
    url_override_path = base64.b64encode(url_override.encode("utf-8")).decode("utf-8")

    if not synchronization.until(
        lambda: len(
            _get_attachments(
                opik_client=opik_client,
                project_id=trace_or_span.project_id,
                entity_type=entity_type,
                entity_id=entity_id,
                url_override_path=url_override_path,
            )
        )
        == len(attachments),
        allow_errors=True,
    ):
        raise AssertionError(
            f"Failed to get all expected attachments for {entity_type} with id {entity_id}."
        )

    attachment_list = _get_attachments(
        opik_client=opik_client,
        project_id=trace_or_span.project_id,
        entity_type=entity_type,
        entity_id=entity_id,
        url_override_path=url_override_path,
    )

    for attachment in attachment_list:
        expected_attachment = attachments.get(attachment.file_name, None)
        assert (
            expected_attachment is not None
        ), f"Attachment {attachment.file_name} not found in expected attachments"

        assert (
            attachment.file_size == data_sizes[expected_attachment.file_name]
        ), f"Wrong size for attachment {attachment.file_name}: {attachment.file_size} != {data_sizes[expected_attachment.file_name]}"

        assert (
            attachment.mime_type == expected_attachment.content_type
        ), f"Wrong content type for attachment {attachment.file_name}: {attachment.mime_type} != {expected_attachment.content_type}"

        assert attachment.link.startswith(
            url_override
        ), f"Wrong link for attachment {attachment.file_name}: {attachment.link} does not start with {url_override}"


def _get_attachments(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    project_id: str,
    url_override_path: str,
) -> List[rest_api_attachment.Attachment]:
    return opik_client._rest_client.attachments.attachment_list(
        project_id=project_id,
        entity_type=entity_type,
        entity_id=entity_id,
        path=url_override_path,
    ).content


def _get_trace_or_span(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
) -> Union[trace_public.TracePublic, span_public.SpanPublic]:
    if entity_type == "trace":
        return opik_client.get_trace_content(id=entity_id)
    elif entity_type == "span":
        return opik_client.get_span_content(id=entity_id)
    else:
        raise ValueError(f"Invalid entity type: {entity_type}")


def _verify_experiment_metadata(
    experiment_content: ExperimentPublic,
    metadata: Optional[Dict[str, Any]],
):
    experiment_metadata = experiment_content.metadata
    if experiment_content.metadata is not None:
        experiment_metadata = {**experiment_content.metadata}
        experiment_metadata.pop("prompt", None)
        experiment_metadata.pop("prompts", None)

    assert experiment_metadata == metadata, f"{experiment_metadata} != {metadata}"


def _verify_experiment_prompts(
    experiment_content: ExperimentPublic,
    prompts: Optional[List[Prompt]],
):
    if prompts is None:
        return

    # asserting Prompt vs Experiment.prompt_version
    experiment_content_prompt_versions = sorted(
        experiment_content.prompt_versions, key=lambda x: x.id
    )
    prompts = sorted(prompts, key=lambda x: x.__internal_api__version_id__)

    for i, prompt in enumerate(prompts):
        assert (
            experiment_content_prompt_versions[i].id
            == prompt.__internal_api__version_id__
        ), f"{experiment_content_prompt_versions[i].id} != {prompt.__internal_api__version_id__}"
        assert (
            experiment_content_prompt_versions[i].prompt_id
            == prompt.__internal_api__prompt_id__
        ), f"{experiment_content_prompt_versions[i].prompt_id} != {prompt.__internal_api__prompt_id__}"

        assert (
            experiment_content_prompt_versions[i].commit == prompt.commit
        ), f"{experiment_content_prompt_versions[i].commit} != {prompt.commit}"

    # check that experiment config/metadata contains Prompt's template
    experiment_prompts = experiment_content.metadata["prompts"]

    for i, prompt in enumerate(prompts):
        assert (
            experiment_prompts[i] == prompt.prompt
        ), f"{experiment_prompts[i]} != {prompt.prompt}"


def verify_optimization(
    opik_client: opik.Opik,
    optimization_id: str,
    name: str = mock.ANY,  # type: ignore
    dataset_name: Optional[str] = mock.ANY,  # type: ignore
    status: Optional[str] = mock.ANY,  # type: ignore
    objective_name: Optional[str] = mock.ANY,  # type: ignore
) -> None:
    if not synchronization.until(
        lambda: (opik_client.get_optimization_by_id(optimization_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get optimization with id {optimization_id}.")

    optimization = opik_client.get_optimization_by_id(optimization_id)

    optimization_content = optimization.fetch_content()

    assert optimization_content.name == name, f"{optimization_content.name} != {name}"

    assert (
        optimization_content.dataset_name == dataset_name
    ), f"{optimization_content.dataset_name} != {dataset_name}"

    assert (
        optimization_content.status == status
    ), f"{optimization_content.status} != {status}"

    assert (
        optimization_content.objective_name == objective_name
    ), f"{optimization_content.objective_name} != {objective_name}"
