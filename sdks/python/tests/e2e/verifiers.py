import base64
import json
import re
from typing import Any, Dict, Iterable, List, Literal, Optional, Set, Union
from unittest import mock

import pytest
import opik
from opik import Attachment, Prompt, ChatPrompt, synchronization
from opik.api_objects.attachment import decoder_helpers
from opik.api_objects.dataset import dataset_item
from opik.rest_api import ExperimentPublic, FeedbackScore, FeedbackScorePublic
from opik.rest_api.types import (
    attachment as rest_api_attachment,
    span_public,
    trace_public,
)
from opik.types import ErrorInfoDict, FeedbackScoreDict
from opik import url_helpers
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

    if hasattr(instance, "model_dump"):
        return instance.model_dump()

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

    testlib.assert_equal(name, trace.name)
    testlib.assert_equal(input, trace.input)
    testlib.assert_equal(output, trace.output)
    testlib.assert_equal(metadata, trace.metadata)

    if tags is not mock.ANY:
        testlib.assert_equal(_try_build_set(tags), _try_build_set(trace.tags))

    if error_info is not mock.ANY:
        testlib.assert_equal(error_info, _try_get__dict__(trace.error_info))

    assert thread_id == trace.thread_id, f"{trace.thread_id} != {thread_id}"

    if project_name is not mock.ANY:
        trace_project = opik_client.get_project(trace.project_id)
        assert trace_project.name == project_name

    if feedback_scores is not mock.ANY:
        _assert_feedback_scores(
            item_id=trace_id,
            feedback_scores=trace.feedback_scores,
            expected_feedback_scores=feedback_scores,
        )

    if guardrails_validations is not mock.ANY:
        if trace.guardrails_validations is None:
            assert guardrails_validations is None, (
                f"Expected guardrails validation to be None, but got {guardrails_validations}"
            )
            return

        actual_guardrails_validations = (
            [] if trace.guardrails_validations is None else trace.guardrails_validations
        )
        assert len(actual_guardrails_validations) == len(guardrails_validations), (
            f"Expected amount of trace guardrails validation ({len(guardrails_validations)}) is not equal to actual amount ({len(actual_guardrails_validations)})"
        )

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
        assert span.parent_span_id == parent_span_id, (
            f"{span.parent_span_id} != {parent_span_id}"
        )

    testlib.assert_equal(name, span.name)
    testlib.assert_equal(type, span.type)

    testlib.assert_equal(input, span.input)
    testlib.assert_equal(output, span.output)
    testlib.assert_equal(metadata, span.metadata)

    if tags is not mock.ANY:
        testlib.assert_equal(_try_build_set(tags), _try_build_set(span.tags))

    if error_info is not mock.ANY:
        testlib.assert_equal(error_info, _try_get__dict__(span.error_info))

    assert span.model == model, f"{span.model} != {model}"
    assert span.provider == provider, f"{span.provider} != {provider}"
    assert span.total_estimated_cost == total_cost, (
        f"{span.total_estimated_cost} != {total_cost}"
    )

    if project_name is not mock.ANY:
        span_project = opik_client.get_project(span.project_id)
        assert span_project.name == project_name

    if feedback_scores is not mock.ANY:
        _assert_feedback_scores(
            item_id=span_id,
            feedback_scores=span.feedback_scores,
            expected_feedback_scores=feedback_scores,
        )


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

    actual_dataset_items = list(
        actual_dataset.__internal_api__stream_items_as_dataclasses__()
    )
    assert len(actual_dataset_items) == len(dataset_items), (
        f"Amount of actual dataset items ({len(actual_dataset_items)}) is not the same as of expected ones ({len(dataset_items)})"
    )

    actual_dataset_items_dicts = [item.model_dump() for item in actual_dataset_items]
    expected_dataset_items_dicts = [item.model_dump() for item in dataset_items]

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
    experiment_scores: Optional[Dict[str, float]] = None,
    experiment_tags: Optional[List[str]] = None,
    dataset_version_id: Optional[str] = mock.ANY,  # type: ignore
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

    assert experiment_content.name == experiment_name, (
        f"{experiment_content.name} != {experiment_name}"
    )

    actual_scores_count = (
        0
        if experiment_content.feedback_scores is None
        else len(experiment_content.feedback_scores)
    )
    assert actual_scores_count == feedback_scores_amount, (
        f"{actual_scores_count} != {feedback_scores_amount}"
    )

    actual_trace_count = (
        0 if experiment_content.trace_count is None else experiment_content.trace_count
    )
    assert actual_trace_count == traces_amount, (
        f"{actual_trace_count} != {traces_amount}"
    )

    _verify_experiment_prompts(experiment_content, prompts)

    _verify_experiment_scores(experiment_content, experiment_scores)

    testlib.assert_equal(expected=experiment_tags, actual=experiment_content.tags)

    if dataset_version_id is not mock.ANY:
        assert experiment_content.dataset_version_id == dataset_version_id, (
            f"Expected dataset_version_id {dataset_version_id}, "
            f"got {experiment_content.dataset_version_id}"
        )


def verify_attachments(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    attachments: Dict[str, Attachment],
    data_sizes: Dict[str, int],
    timeout: int = 10,
) -> None:
    attachment_list = _wait_for_attachments_list(
        opik_client=opik_client,
        entity_type=entity_type,
        entity_id=entity_id,
        expected_size=len(attachments),
        timeout=timeout,
    )
    url_override = opik_client._config.url_override

    for attachment in attachment_list:
        expected_attachment = attachments.get(attachment.file_name, None)
        assert expected_attachment is not None, (
            f"Attachment {attachment.file_name} not found in expected attachments"
        )

        assert attachment.file_size == data_sizes[expected_attachment.file_name], (
            f"Wrong size for attachment {attachment.file_name}: {attachment.file_size} != {data_sizes[expected_attachment.file_name]}"
        )

        assert attachment.mime_type == expected_attachment.content_type, (
            f"Wrong content type for attachment {attachment.file_name}: {attachment.mime_type} != {expected_attachment.content_type}"
        )

        if not url_helpers.is_aws_presigned_url(attachment.link):
            assert attachment.link.startswith(url_override), (
                f"Wrong link for attachment {attachment.file_name}: {attachment.link} does not start with {url_override}"
            )


def verify_auto_extracted_attachments(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    expected_sizes: List[int],
    timeout: int = 10,
) -> None:
    attachment_list = _wait_for_attachments_list(
        opik_client=opik_client,
        entity_type=entity_type,
        entity_id=entity_id,
        expected_size=len(expected_sizes),
        timeout=timeout,
    )

    file_name_pattern = re.compile(decoder_helpers.ATTACHMENT_FILE_NAME_REGEX)

    for attachment in attachment_list:
        assert attachment.file_size in expected_sizes, (
            f"Wrong size for attachment {attachment.file_name}: {attachment.file_size} not in {expected_sizes}"
        )
        assert file_name_pattern.match(attachment.file_name), (
            f"Wrong file name for attachment {attachment.file_name} - it does not match {file_name_pattern.pattern}"
        )


def _wait_for_attachments_list(
    opik_client: opik.Opik,
    entity_type: Literal["trace", "span"],
    entity_id: str,
    expected_size: int,
    timeout: int,
) -> List[Attachment]:
    if not synchronization.until(
        lambda: (
            _get_trace_or_span(
                opik_client, entity_type=entity_type, entity_id=entity_id
            )
            is not None
        ),
        allow_errors=True,
        max_try_seconds=timeout,
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
        == expected_size,
        allow_errors=True,
        max_try_seconds=timeout,
    ):
        raise AssertionError(
            f"Failed to get all expected attachments for {entity_type} with id {entity_id}."
        )

    return _get_attachments(
        opik_client=opik_client,
        project_id=trace_or_span.project_id,
        entity_type=entity_type,
        entity_id=entity_id,
        url_override_path=url_override_path,
    )


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
        ), (
            f"{experiment_content_prompt_versions[i].id} != {prompt.__internal_api__version_id__}"
        )
        assert (
            experiment_content_prompt_versions[i].prompt_id
            == prompt.__internal_api__prompt_id__
        ), (
            f"{experiment_content_prompt_versions[i].prompt_id} != {prompt.__internal_api__prompt_id__}"
        )

        assert experiment_content_prompt_versions[i].commit == prompt.commit, (
            f"{experiment_content_prompt_versions[i].commit} != {prompt.commit}"
        )

    # check that experiment config/metadata contains Prompt's template
    experiment_prompts = experiment_content.metadata["prompts"]

    for prompt in prompts:
        assert experiment_prompts[prompt.name] == prompt.prompt, (
            f"{experiment_prompts[prompt.name]} != {prompt.prompt}"
        )


def _verify_experiment_scores(
    experiment_content: ExperimentPublic,
    experiment_scores: Optional[Dict[str, float]],
):
    """Verify experiment-level scores match expected values."""
    if experiment_scores is None:
        return

    actual_experiment_scores = experiment_content.experiment_scores

    assert actual_experiment_scores is not None, (
        f"Expected experiment_scores to be set, but got None. "
        f"Experiment ID: {experiment_content.id}, "
        f"Expected scores: {experiment_scores}"
    )

    # Create a dict of actual scores for easy comparison
    actual_scores_dict = {score.name: score.value for score in actual_experiment_scores}

    assert len(actual_scores_dict) == len(experiment_scores), (
        f"Expected {len(experiment_scores)} experiment scores, "
        f"but got {len(actual_scores_dict)}. "
        f"Expected: {experiment_scores}, "
        f"Actual: {actual_scores_dict}"
    )

    for expected_name, expected_value in experiment_scores.items():
        assert expected_name in actual_scores_dict, (
            f"Expected experiment score '{expected_name}' not found. "
            f"Available scores: {list(actual_scores_dict.keys())}"
        )

        assert actual_scores_dict[expected_name] == expected_value, (
            f"Expected experiment score '{expected_name}' to have value {expected_value}, "
            f"but got {actual_scores_dict[expected_name]}"
        )


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

    assert optimization_content.dataset_name == dataset_name, (
        f"{optimization_content.dataset_name} != {dataset_name}"
    )

    assert optimization_content.status == status, (
        f"{optimization_content.status} != {status}"
    )

    assert optimization_content.objective_name == objective_name, (
        f"{optimization_content.objective_name} != {objective_name}"
    )


def verify_thread(
    opik_client: opik.Opik,
    thread_id: str,
    project_name: Optional[str] = None,
    feedback_scores: List[FeedbackScoreDict] = mock.ANY,  # type: ignore
) -> None:
    threads_client = opik_client.get_threads_client()
    if not synchronization.until(
        lambda: (
            len(
                threads_client.search_threads(
                    project_name=project_name, filter_string=f'id = "{thread_id}"'
                )
            )
            == 1
        )
    ):
        raise AssertionError(f"Failed to get thread with id '{thread_id}'.")
    threads = threads_client.search_threads(
        project_name=project_name,
        filter_string=f'id = "{thread_id}"',
    )
    assert len(threads) == 1

    thread = threads[0]
    assert thread.id == thread_id

    def _get_feedback_scores() -> Optional[List[Union[FeedbackScore]]]:
        return threads_client.search_threads(
            project_name=project_name,
            filter_string=f'id = "{thread_id}"',
        )[0].feedback_scores

    if feedback_scores is not mock.ANY:
        # wait for feedback scores to propagate
        if not synchronization.until(lambda: (_get_feedback_scores() is not None)):
            raise AssertionError(
                f"Failed to get feedback scores for thread with id '{thread_id}'."
            )

        actual_feedback_scores = _get_feedback_scores()
        _assert_feedback_scores(
            item_id=thread_id,
            feedback_scores=actual_feedback_scores,
            expected_feedback_scores=feedback_scores,
        )


def _assert_feedback_scores(
    item_id: str,
    feedback_scores: Optional[List[Union[FeedbackScore, FeedbackScorePublic]]],
    expected_feedback_scores: Optional[List[FeedbackScoreDict]],
) -> None:
    if feedback_scores is None:
        assert expected_feedback_scores is None, (
            f"Expected feedback scores to be None, but got {expected_feedback_scores}"
        )
        return

    actual_feedback_scores = [] if feedback_scores is None else feedback_scores
    assert len(actual_feedback_scores) == len(expected_feedback_scores), (
        f"Expected amount of feedback scores ({len(expected_feedback_scores)}) is not equal to actual amount ({len(actual_feedback_scores)})"
    )

    actual_feedback_scores: List[FeedbackScoreDict] = [
        FeedbackScoreDict(
            category_name=score.category_name,
            id=item_id,
            name=score.name,
            reason=score.reason.strip(),
            value=score.value,
        )
        for score in feedback_scores
    ]

    sorted_actual_feedback_scores = sorted(
        actual_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
    )
    sorted_expected_feedback_scores = sorted(
        expected_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
    )
    for actual_score, expected_score in zip(
        sorted_actual_feedback_scores, sorted_expected_feedback_scores
    ):
        testlib.assert_dicts_equal(actual_score, expected_score, ignore_keys=["value"])
        assert expected_score["value"] == pytest.approx(
            actual_score["value"], abs=0.0001
        )


def verify_prompt_version(
    prompt: Prompt,
    *,
    name: Any = mock.ANY,  # type: ignore
    template: Any = mock.ANY,  # type: ignore
    type: Any = mock.ANY,  # type: ignore
    metadata: Any = mock.ANY,  # type: ignore
    version_id: Any = mock.ANY,  # type: ignore
    prompt_id: Any = mock.ANY,  # type: ignore
    commit: Any = mock.ANY,  # type: ignore
) -> None:
    testlib.assert_equal(name, prompt.name)
    testlib.assert_equal(template, prompt.prompt)
    testlib.assert_equal(type, prompt.type)
    testlib.assert_equal(metadata, prompt.metadata)
    assert version_id == prompt.__internal_api__version_id__, (
        f"{prompt.__internal_api__version_id__} != {version_id}"
    )
    assert prompt_id == prompt.__internal_api__prompt_id__, (
        f"{prompt.__internal_api__prompt_id__} != {prompt_id}"
    )
    assert commit == prompt.commit, f"{prompt.commit} != {commit}"


def verify_chat_prompt_version(
    chat_prompt: ChatPrompt,
    *,
    name: Any = mock.ANY,  # type: ignore
    messages: Any = mock.ANY,  # type: ignore
    type: Any = mock.ANY,  # type: ignore
    metadata: Any = mock.ANY,  # type: ignore
    version_id: Any = mock.ANY,  # type: ignore
    prompt_id: Any = mock.ANY,  # type: ignore
    commit: Any = mock.ANY,  # type: ignore
) -> None:
    """
    Verifies that a ChatPrompt has the expected properties.

    This verifier checks all the same fields as verify_prompt_version but adapted for ChatPrompt:
    - messages instead of template
    - template_structure field is always verified to be "chat"
    """
    testlib.assert_equal(name, chat_prompt.name)
    testlib.assert_equal(messages, chat_prompt.template)
    testlib.assert_equal(type, chat_prompt.type)
    testlib.assert_equal(metadata, chat_prompt.metadata)
    assert version_id == chat_prompt.__internal_api__version_id__, (
        f"{chat_prompt.__internal_api__version_id__} != {version_id}"
    )
    assert prompt_id == chat_prompt.__internal_api__prompt_id__, (
        f"{chat_prompt.__internal_api__prompt_id__} != {prompt_id}"
    )
    assert commit == chat_prompt.commit, f"{chat_prompt.commit} != {commit}"


def verify_dataset_filtered_items(
    opik_client: opik.Opik,
    dataset_name: str,
    filter_string: str,
    expected_count: int,
    expected_inputs: Set[str],
) -> None:
    """
    Verifies that filtering dataset items with filter_string returns the expected results.

    Args:
        opik_client: The Opik client instance
        dataset_name: The name of the dataset to retrieve
        filter_string: The filter string to apply
        expected_count: Expected number of items matching the filter
        expected_inputs: Set of expected question strings from input field
    """
    dataset = opik_client.get_dataset(name=dataset_name)

    filtered_items = dataset.get_items(filter_string=filter_string)
    assert len(filtered_items) == expected_count, (
        f"Expected {expected_count} items, got {len(filtered_items)}"
    )

    if expected_count > 0:
        inputs = {item["input"]["question"] for item in filtered_items}
        assert inputs == expected_inputs, (
            f"Input mismatch: {inputs} != {expected_inputs}"
        )


def verify_traces_annotation_queue(
    opik_client: opik.Opik,
    queue_id: str,
    name: str = mock.ANY,  # type: ignore
    scope: str = mock.ANY,  # type: ignore
    description: Optional[str] = mock.ANY,  # type: ignore
    instructions: Optional[str] = mock.ANY,  # type: ignore
) -> None:
    if not synchronization.until(
        lambda: (opik_client.get_traces_annotation_queue(queue_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get annotation queue with id {queue_id}.")

    queue = opik_client.get_traces_annotation_queue(queue_id)

    assert queue.id is not None, "Queue id should not be None"
    testlib.assert_equal(name, queue.name)
    testlib.assert_equal(scope, queue.scope)
    testlib.assert_equal(description, queue.description)
    testlib.assert_equal(instructions, queue.instructions)


def verify_threads_annotation_queue(
    opik_client: opik.Opik,
    queue_id: str,
    name: str = mock.ANY,  # type: ignore
    scope: str = mock.ANY,  # type: ignore
    description: Optional[str] = mock.ANY,  # type: ignore
    instructions: Optional[str] = mock.ANY,  # type: ignore
) -> None:
    if not synchronization.until(
        lambda: (opik_client.get_threads_annotation_queue(queue_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get annotation queue with id {queue_id}.")

    queue = opik_client.get_threads_annotation_queue(queue_id)

    assert queue.id is not None, "Queue id should not be None"
    testlib.assert_equal(name, queue.name)
    testlib.assert_equal(scope, queue.scope)
    testlib.assert_equal(description, queue.description)
    testlib.assert_equal(instructions, queue.instructions)
