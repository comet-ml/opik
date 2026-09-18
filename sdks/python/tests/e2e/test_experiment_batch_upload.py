"""`Experiment.batch_upload_items` against a real backend.

The unit tests drive the upload through a mock transport and assert on the body they
captured: the envelope built by hand around orjson-serialised fragments, the gzip level
and the `Content-Encoding` that labels it, the headers `wrapper_headers` recovers and
the transport `upload_transport` resolves. A body the backend would reject -- a wrong
envelope key, a missing header, an omitted field sent as null -- passes every one of
them. These tests send it to a server and read back what the server stored.
"""

import collections
import datetime
from typing import Any, Callable, Dict, Iterator, List, Tuple
from unittest import mock

import pytest

import opik
import opik.exceptions
from opik import id_helpers, synchronization
from opik.api_objects import constants
from opik.api_objects.dataset import dataset as dataset_module
from opik.api_objects.experiment import experiment as experiment_module
from opik.api_objects.experiment import experiment_item
from opik.rest_api import client as rest_api_client
from opik.types import FeedbackScoreDict

from . import verifiers
from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)

_START = datetime.datetime(2024, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)
_END = _START + datetime.timedelta(seconds=1)


def _create_dataset(
    opik_client: opik.Opik, name: str, item_count: int
) -> Tuple[dataset_module.Dataset, Dict[int, str]]:
    """A dataset of `item_count` items, and each item's id keyed by its index."""
    dataset = opik_client.create_dataset(name, project_name=PROJECT_NAME)
    dataset.insert({"input": {"index": index}} for index in range(item_count))

    items: List[Dict[str, Any]] = []

    def _all_readable() -> bool:
        nonlocal items
        items = dataset.get_items()
        return len(items) == item_count

    assert synchronization.until(_all_readable, max_try_seconds=60), (
        f"Only {len(items)} of {item_count} dataset items became readable"
    )
    return dataset, {item["input"]["index"]: item["id"] for item in items}


def _wait_for_experiment_items(
    experiment: experiment_module.Experiment, expected: int
) -> List[experiment_item.ExperimentItemContent]:
    """Every stored item of the experiment, once at least `expected` are readable.

    Reads up to twice as many as expected, so a duplicated item is returned rather
    than cut off by the limit and the caller's exactly-once check can see it.
    """
    items: List[experiment_item.ExperimentItemContent] = []

    def _readable() -> bool:
        nonlocal items
        items = experiment.get_items(max_results=expected * 2, truncate=True)
        return len(items) >= expected

    assert synchronization.until(_readable, max_try_seconds=60, allow_errors=True), (
        f"Only {len(items)} of {expected} experiment items became readable"
    )
    return items


def _assert_each_dataset_item_once(
    items: List[experiment_item.ExperimentItemContent], expected_ids: List[str]
) -> None:
    """Every expected dataset item is linked exactly once -- no loss, no duplicate."""
    counts = collections.Counter(item.dataset_item_id for item in items)
    duplicated = {key: count for key, count in counts.items() if count > 1}
    assert not duplicated, f"Experiment items stored more than once: {duplicated}"
    assert set(counts) == set(expected_ids), (
        f"missing {len(set(expected_ids) - set(counts))}, "
        f"unexpected {len(set(counts) - set(expected_ids))}"
    )


def _score(index: int) -> FeedbackScoreDict:
    return {"name": "parity", "value": float(index % 2), "reason": f"item {index}"}


@pytest.mark.parametrize("num_threads", [1, 4])
def test_batch_upload_items__generator_source__every_item_lands_exactly_once(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str, num_threads: int
):
    """A one-shot generator uploaded to a real backend, across several requests.

    This is the streaming upload end to end: the envelope built around orjson
    fragments, the explicit gzip and the `Content-Encoding` that labels it, and the
    auth headers `wrapper_headers` supplies are only ever asserted against a captured
    body in the unit tests, so a body the backend rejects would not fail any of them.
    2,500 items is three requests at the 1000-item cap, and both thread counts are
    covered because one sends inline and the other hands bodies to the send pool.

    A generator is the input this path could not take at all before: the method
    typed its argument `List` and called `len()` on it.
    """
    item_count = 2_500
    dataset, ids_by_index = _create_dataset(opik_client, dataset_name, item_count)
    experiment = opik_client.create_experiment(
        dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
    )
    span_name = f"bulk-span-{experiment_name}"

    def source() -> Iterator[opik.ExperimentItemBulkRecord]:
        for index in range(item_count):
            yield opik.ExperimentItemBulkRecord(
                dataset_item_id=ids_by_index[index],
                trace=opik.ExperimentItemBulkTrace(
                    name="bulk-trace",
                    start_time=_START,
                    end_time=_END,
                    input={"index": index},
                    output={"answer": f"answer {index}"},
                ),
                spans=[
                    opik.ExperimentItemBulkSpan(
                        name=span_name,
                        type="llm",
                        start_time=_START,
                        end_time=_END,
                        input={"index": index},
                        output={"answer": f"answer {index}"},
                    )
                ],
                feedback_scores=[_score(index)],
            )

    experiment.batch_upload_items(
        source(), project_name=PROJECT_NAME, num_threads=num_threads
    )

    stored = _wait_for_experiment_items(experiment, item_count)
    _assert_each_dataset_item_once(stored, list(ids_by_index.values()))

    # Each stored item carries the trace and score that were sent for its dataset
    # item, not merely a row: the content has to survive the hand-built body.
    index_by_id = {item_id: index for index, item_id in ids_by_index.items()}
    for item in stored:
        index = index_by_id[item.dataset_item_id]
        assert item.evaluation_task_output == {"answer": f"answer {index}"}
        assert [
            (score["name"], score["value"], score["reason"])
            for score in item.feedback_scores
        ] == [("parity", float(index % 2), f"item {index}")]

    trace_ids = {item.trace_id for item in stored}
    assert len(trace_ids) == item_count, "Every item must have its own trace"

    # One span per trace, attached to the trace its record sent.
    spans: List[Any] = []

    def _spans_readable() -> bool:
        nonlocal spans
        spans = opik_client.search_spans(
            project_name=PROJECT_NAME,
            filter_string=f'name = "{span_name}"',
            max_results=item_count * 2,
        )
        return len(spans) >= item_count

    assert synchronization.until(_spans_readable, max_try_seconds=60), (
        f"Only {len(spans)} of {item_count} spans became readable"
    )
    assert len(spans) == item_count
    assert {span.trace_id for span in spans} == trace_ids


def _content_records(
    ids_by_index: Dict[int, str], trace_id: str, parent_span_id: str, child_span_id: str
) -> List[opik.ExperimentItemBulkRecord]:
    """One record per shape the backend accepts, with non-ASCII text throughout.

    orjson writes non-ASCII as raw UTF-8 rather than as `\\u` escapes, so the text is
    what shows whether the body's encoding is declared and decoded the way it was
    written.
    """
    return [
        # No trace: the backend creates one, with this as its output.
        opik.ExperimentItemBulkRecord(
            dataset_item_id=ids_by_index[0],
            evaluate_task_result={"answer": "Київ — 東京 — ✓ 🚀"},
            feedback_scores=[{"name": "exact_match", "value": 1.0, "reason": "збіг ✓"}],
        ),
        opik.ExperimentItemBulkRecord(
            dataset_item_id=ids_by_index[1],
            trace=opik.ExperimentItemBulkTrace(
                id=trace_id,
                name="bulk-trace ✓",
                start_time=_START,
                end_time=_END,
                input={"question": "Столиця Японії?"},
                output={"answer": "東京 🗼"},
                metadata={"model": "gpt-ü"},
                tags=["bulk", "тег"],
            ),
            spans=[
                opik.ExperimentItemBulkSpan(
                    id=parent_span_id,
                    name="agent ✓",
                    type="general",
                    start_time=_START,
                    end_time=_END,
                    input={"question": "Столиця Японії?"},
                    output={"answer": "東京 🗼"},
                ),
                opik.ExperimentItemBulkSpan(
                    id=child_span_id,
                    parent_span_id=parent_span_id,
                    name="llm-call",
                    type="llm",
                    start_time=_START,
                    end_time=_END,
                    input={"prompt": "Столиця Японії?"},
                    output={"completion": "東京"},
                    model="gpt-4o",
                    provider="openai",
                    usage={
                        "prompt_tokens": 3,
                        "completion_tokens": 5,
                        "total_tokens": 8,
                    },
                ),
            ],
            feedback_scores=[
                {"name": "exact_match", "value": 1.0, "reason": "東京 ✓"},
                {"name": "fluency", "value": 0.5},
            ],
        ),
    ]


_SOURCES: Dict[str, Callable[[List[Any]], Any]] = {
    "list": list,
    "generator": lambda records: (record for record in records),
}


@pytest.mark.parametrize(
    "source_kind, validate_before_upload",
    [("list", True), ("list", False), ("generator", True)],
)
def test_batch_upload_items__every_record_shape__stores_exactly_what_was_sent(
    opik_client: opik.Opik,
    dataset_name: str,
    experiment_name: str,
    source_kind: str,
    validate_before_upload: bool,
):
    """Both record shapes, from each kind of source, store the same content.

    A list with the default takes the eager path -- validated and sized in full before
    anything is sent. A list with `validate_before_upload=False` and a generator both
    take the single-pass one, validated as each item is reached. Each case asserts
    the same expected content, so they match one another as well as the input.

    `evaluate_task_result` next to no trace, and a trace next to no
    `evaluate_task_result`, is where omitted-versus-null matters: the backend reads an
    explicit null as a value and rejects the record for carrying both.
    """
    dataset, ids_by_index = _create_dataset(opik_client, dataset_name, 2)
    experiment = opik_client.create_experiment(
        dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
    )
    trace_id = id_helpers.generate_id()
    parent_span_id = id_helpers.generate_id()
    child_span_id = id_helpers.generate_id()
    records = _content_records(ids_by_index, trace_id, parent_span_id, child_span_id)

    experiment.batch_upload_items(
        _SOURCES[source_kind](records),
        project_name=PROJECT_NAME,
        validate_before_upload=validate_before_upload,
    )

    stored = {
        item.dataset_item_id: item
        for item in _wait_for_experiment_items(experiment, len(records))
    }
    _assert_each_dataset_item_once(list(stored.values()), list(ids_by_index.values()))

    task_result_item = stored[ids_by_index[0]]
    assert task_result_item.evaluation_task_output == {"answer": "Київ — 東京 — ✓ 🚀"}
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=task_result_item.trace_id,
        output={"answer": "Київ — 東京 — ✓ 🚀"},
        feedback_scores=[
            {
                "category_name": None,
                "id": task_result_item.trace_id,
                "name": "exact_match",
                "reason": "збіг ✓",
                "value": 1.0,
            }
        ],
        project_name=PROJECT_NAME,
    )

    traced_item = stored[ids_by_index[1]]
    assert traced_item.trace_id == trace_id, "The trace id sent must be the one stored"
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace_id,
        name="bulk-trace ✓",
        input={"question": "Столиця Японії?"},
        output={"answer": "東京 🗼"},
        tags=["bulk", "тег"],
        feedback_scores=[
            {
                "category_name": None,
                "id": trace_id,
                "name": "exact_match",
                "reason": "東京 ✓",
                "value": 1.0,
            },
            {
                "category_name": None,
                "id": trace_id,
                "name": "fluency",
                "reason": None,
                "value": 0.5,
            },
        ],
        project_name=PROJECT_NAME,
    )
    # Compared by key: the backend adds the span providers it saw to the metadata.
    assert opik_client.get_trace_content(trace_id).metadata["model"] == "gpt-ü"
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=parent_span_id,
        trace_id=trace_id,
        parent_span_id=None,
        name="agent ✓",
        type="general",
        input={"question": "Столиця Японії?"},
        output={"answer": "東京 🗼"},
        model=None,
        provider=None,
        total_cost=None,
        project_name=PROJECT_NAME,
    )
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=child_span_id,
        trace_id=trace_id,
        parent_span_id=parent_span_id,
        name="llm-call",
        type="llm",
        input={"prompt": "Столиця Японії?"},
        output={"completion": "東京"},
        model="gpt-4o",
        provider="openai",
        # Estimated from the model and usage by the backend, which is not what is under test.
        total_cost=mock.ANY,
        project_name=PROJECT_NAME,
    )


def test_batch_upload_items__single_pass_source_with_invalid_item__earlier_batches_persist(
    opik_client: opik.Opik,
    dataset_name: str,
    experiment_name: str,
    monkeypatch: pytest.MonkeyPatch,
):
    """A bad item deep in a generator raises, with the batches before it stored.

    A single-pass source cannot be validated up front, so the item is found when it
    is reached and whatever was already sent stays sent. With a 10-item cap, items
    0-19 close two batches before item 25 is reached; items 20-24 are still in the
    open batch, which is dropped rather than sent. One thread, so what has landed by
    then does not depend on scheduling.
    """
    monkeypatch.setattr(constants, "EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE", 10)
    dataset, ids_by_index = _create_dataset(opik_client, dataset_name, 26)
    experiment = opik_client.create_experiment(
        dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
    )

    def source() -> Iterator[opik.ExperimentItemBulkRecord]:
        for index in range(25):
            yield opik.ExperimentItemBulkRecord(
                dataset_item_id=ids_by_index[index],
                evaluate_task_result={"answer": f"answer {index}"},
            )
        # Both result forms at once, which the backend would reject for the whole batch.
        yield opik.ExperimentItemBulkRecord(
            dataset_item_id=ids_by_index[25],
            evaluate_task_result={"answer": "answer 25"},
            trace=opik.ExperimentItemBulkTrace(start_time=_START),
        )

    with pytest.raises(opik.exceptions.ValidationError, match=r"items\[25\]"):
        experiment.batch_upload_items(
            source(), project_name=PROJECT_NAME, num_threads=1
        )

    stored = _wait_for_experiment_items(experiment, 20)
    _assert_each_dataset_item_once(stored, [ids_by_index[index] for index in range(20)])


def test_batch_upload_items__batch_over_the_server_cap__is_split_and_lands_once(
    opik_client: opik.Opik,
    dataset_name: str,
    experiment_name: str,
    monkeypatch: pytest.MonkeyPatch,
):
    """A batch the server rejects as too large is halved until it fits, with no duplicate.

    The SDK's own size cap is raised above the whole upload, so the 18 MB of records
    go out as one batch and it is the backend's `@MaxRequestSize` that refuses it,
    with the 422 the split has to recognise. 18 MB is above the standard 4 MB limit
    with room for a deployment that raised it. The filler is repetitive, so the wire
    body is tens of KB gzipped; the server's check measures the parsed request.

    Trace ids are set, so the stored ids can be compared with the ones sent: a half is
    re-sent from bytes already serialised, and nothing may be minted twice.
    """
    monkeypatch.setattr(constants, "EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB", 64)
    rejections: List[bool] = []
    is_batch_too_large = experiment_module._is_batch_too_large

    def _recording_is_batch_too_large(error: Any) -> bool:
        too_large = is_batch_too_large(error)
        rejections.append(too_large)
        return too_large

    monkeypatch.setattr(
        experiment_module, "_is_batch_too_large", _recording_is_batch_too_large
    )

    item_count = 24
    dataset, ids_by_index = _create_dataset(opik_client, dataset_name, item_count)
    experiment = opik_client.create_experiment(
        dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
    )
    trace_ids = {index: id_helpers.generate_id() for index in range(item_count)}
    filler = "x" * 750_000

    experiment.batch_upload_items(
        [
            opik.ExperimentItemBulkRecord(
                dataset_item_id=ids_by_index[index],
                trace=opik.ExperimentItemBulkTrace(
                    id=trace_ids[index],
                    start_time=_START,
                    end_time=_END,
                    output={"answer": f"answer {index}"},
                    metadata={"filler": filler},
                ),
            )
            for index in range(item_count)
        ],
        project_name=PROJECT_NAME,
    )

    assert rejections and all(rejections), (
        f"Expected the server to reject the batch as too large, got {rejections}"
    )

    stored = _wait_for_experiment_items(experiment, item_count)
    _assert_each_dataset_item_once(stored, list(ids_by_index.values()))
    assert {item.dataset_item_id: item.trace_id for item in stored} == {
        ids_by_index[index]: trace_ids[index] for index in range(item_count)
    }


def test_batch_upload_items__request_compression_disabled__items_are_still_stored(
    dataset_name: str, experiment_name: str, monkeypatch: pytest.MonkeyPatch
):
    """An uncompressed body has to be accepted too, and labelled as such.

    With compression off the body goes out as plain bytes and `send_prepared_json`
    omits `Content-Encoding`. Getting that pairing wrong -- gzipped bytes labelled
    plain, or the reverse -- is invisible to a test that decodes the body it captured.
    """
    monkeypatch.setenv("OPIK_ENABLE_JSON_REQUEST_COMPRESSION", "false")

    uncompressed_client = opik.Opik()
    try:
        dataset, ids_by_index = _create_dataset(uncompressed_client, dataset_name, 3)
        experiment = uncompressed_client.create_experiment(
            dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
        )
        experiment.batch_upload_items(
            [
                opik.ExperimentItemBulkRecord(
                    dataset_item_id=item_id,
                    evaluate_task_result={"answer": f"answer {index}"},
                )
                for index, item_id in ids_by_index.items()
            ],
            project_name=PROJECT_NAME,
        )

        stored = _wait_for_experiment_items(experiment, 3)
        _assert_each_dataset_item_once(stored, list(ids_by_index.values()))
    finally:
        uncompressed_client.end(flush=False)


def test_batch_upload_items__standalone_rest_client__uploads_authenticated(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """An experiment on a REST client configured on its own uploads with its credentials.

    `Opik` puts auth and workspace headers on the httpx client, so an experiment built
    from its REST client would upload authenticated whatever the sender did. A public
    `OpikApi` built directly keeps them on the wrapper, and the generated client
    applies them per request -- so this is the construction that catches a
    prepared-body sender that forgets them.
    """
    config = opik_client.config
    rest_client = rest_api_client.OpikApi(
        base_url=config.url_override,
        api_key=config.api_key,
        workspace_name=config.workspace,
    )
    try:
        dataset, ids_by_index = _create_dataset(opik_client, dataset_name, 3)
        created = opik_client.create_experiment(
            dataset_name=dataset.name, name=experiment_name, project_name=PROJECT_NAME
        )
        standalone = experiment_module.Experiment(
            id=created.id,
            name=created.name,
            dataset_name=dataset.name,
            rest_client=rest_client,
            streamer=opik_client._streamer,
            experiments_client=opik_client.get_experiments_client(),
            project_name=PROJECT_NAME,
        )
        standalone.batch_upload_items(
            [
                opik.ExperimentItemBulkRecord(
                    dataset_item_id=item_id,
                    evaluate_task_result={"answer": f"answer {index}"},
                )
                for index, item_id in ids_by_index.items()
            ],
            project_name=PROJECT_NAME,
        )

        stored = _wait_for_experiment_items(created, 3)
        _assert_each_dataset_item_once(stored, list(ids_by_index.values()))
    finally:
        rest_client._client_wrapper.httpx_client.httpx_client.close()
