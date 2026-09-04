import logging
import time

import opik
import opik.exceptions
from opik import synchronization

from opik.api_objects.dataset import dataset_item
from opik.api_objects import constants, helpers
from . import verifiers
from ..testlib import generate_project_name
import pytest

LOGGER = logging.getLogger(__name__)

PROJECT_NAME = generate_project_name("e2e", __name__)


def test_create_and_populate_dataset__happyflow(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
            {
                "input": {"question": "What is the of capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
            },
        ]
    )

    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of France?"},
            expected_output={"output": "Paris"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Germany?"},
            expected_output={"output": "Berlin"},
        ),
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Poland?"},
            expected_output={"output": "Warsaw"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
        project_name=PROJECT_NAME,
    )


def test_insert_and_update_item__dataset_size_should_be_the_same__an_item_with_the_same_id_should_have_new_content(
    opik_client: opik.Opik, dataset_name: str
):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    ITEM_ID = helpers.generate_id()
    dataset.insert(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of France?"},
            },
        ]
    )
    dataset.update(
        [
            {
                "id": ITEM_ID,
                "input": {"question": "What is the of capital of Belarus?"},
            },
        ]
    )
    EXPECTED_DATASET_ITEMS = [
        dataset_item.DatasetItem(
            input={"question": "What is the of capital of Belarus?"},
        ),
    ]

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=EXPECTED_DATASET_ITEMS,
        project_name=PROJECT_NAME,
    )


def test_deduplication(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    item = {
        "user_input": {"question": "What is the of capital of France?"},
        "expected_model_output": {"output": "Paris"},
    }

    # Write the dataset
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert([item])

    # Read the dataset and insert the same item
    new_dataset = opik_client.get_dataset(dataset_name, project_name=PROJECT_NAME)
    new_dataset.insert([item])

    # Verify the dataset
    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[
            dataset_item.DatasetItem(**item),
        ],
        project_name=PROJECT_NAME,
    )


@pytest.mark.parametrize("num_threads", [1, 8])
def test_insert_parallel__same_data_regardless_of_thread_count(
    opik_client: opik.Opik, dataset_name: str, num_threads: int
):
    """Parallel insert must produce the same dataset content, item count and
    a single version whether it runs sequentially or across worker threads.

    Sequential (1) and parallel (8) are compared: 10k items yield 10 batches
    at the 1000-rows/batch cap, so the parallel run fans real work across the
    pool; payload is kept tiny so total bytes stay small for CI. Timing is
    logged (not asserted): CI runs a single backend container, so it is
    backend-bound and understates the speedup — the real throughput gain is
    measured ad-hoc against a resourced test environment. What CI guarantees
    is that correctness holds identically whatever the thread count.
    """
    DESCRIPTION = "E2E parallel insert dataset"
    N_ITEMS = (
        10_000  # 10 batches at the 1000-rows/batch cap -> real fan-out at 8 workers
    )

    name = f"{dataset_name}-t{num_threads}"
    items = [
        {
            "input": {"question": f"question {i}"},
            "expected_output": {"output": f"answer {i}"},
        }
        for i in range(N_ITEMS)
    ]
    expected_items = [dataset_item.DatasetItem(**item) for item in items]

    dataset = opik_client.create_dataset(
        name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    start = time.perf_counter()
    dataset.insert(items, num_threads=num_threads)
    elapsed = time.perf_counter() - start
    LOGGER.info(
        "Parallel insert of %d items with num_threads=%d took %.2fs (%.0f rows/s)",
        N_ITEMS,
        num_threads,
        elapsed,
        N_ITEMS / elapsed if elapsed else 0,
    )

    # All items persisted server-side, exactly once, with identical content.
    verifiers.verify_dataset(
        opik_client=opik_client,
        name=name,
        description=DESCRIPTION,
        dataset_items=expected_items,
        project_name=PROJECT_NAME,
    )

    # Shared batch_group_id => a single version, no matter the thread count.
    # (Unique-per-chunk grouping would create one version per batch.)
    # Skipped when versioning is disabled on the backend (get_version_info
    # returns None); the count + content checks above already prove correctness.
    stored_dataset = opik_client.get_dataset(name=name, project_name=PROJECT_NAME)
    version_info = stored_dataset.get_version_info()
    if version_info is not None:
        assert version_info.version_name == "v1", (
            "Parallel insert must fold all batches into one version regardless of thread count"
        )
        assert version_info.items_total == N_ITEMS


@pytest.mark.parametrize("num_threads", [1, 4])
def test_insert_generator__same_data_as_a_list(
    opik_client: opik.Opik, dataset_name: str, num_threads: int
):
    """A one-shot generator must land the same dataset a list does.

    `insert` consumes its argument lazily and uploads as it reads, so the
    producer runs interleaved with the uploads against a real backend rather
    than being drained first. Enough items to cross the batch cap in both
    modes, so the sequential and the fanned-out paths each exercise a real
    multi-batch stream.
    """
    DESCRIPTION = "E2E generator insert dataset"
    N_ITEMS = 2_500

    name = f"{dataset_name}-gen-t{num_threads}"
    expected_items = [
        dataset_item.DatasetItem(
            input={"question": f"question {i}"},
            expected_output={"output": f"answer {i}"},
        )
        for i in range(N_ITEMS)
    ]

    def item_source():
        for i in range(N_ITEMS):
            yield {
                "input": {"question": f"question {i}"},
                "expected_output": {"output": f"answer {i}"},
            }

    dataset = opik_client.create_dataset(
        name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    dataset.insert(item_source(), num_threads=num_threads, deduplication=False)

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=name,
        description=DESCRIPTION,
        dataset_items=expected_items,
        project_name=PROJECT_NAME,
    )


def test_dataset_clearing(opik_client: opik.Opik, dataset_name: str):
    DESCRIPTION = "E2E test dataset"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    dataset.insert(
        [
            {
                "input": {"question": "What is the of capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is the of capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
        ]
    )
    dataset.clear()

    verifiers.verify_dataset(
        opik_client=opik_client,
        name=dataset_name,
        description=DESCRIPTION,
        dataset_items=[],
        project_name=PROJECT_NAME,
    )


def test_get_items_with_filter__returns_filtered_items(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_items with filter_string returns correct filtered items."""
    DESCRIPTION = "E2E test dataset for filtering"

    # Create dataset with items that have different data.category values
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
                "category": "geography",
            },
            {
                "input": {"question": "What is 2 + 2?"},
                "expected_output": {"output": "4"},
                "category": "math",
            },
            {
                "input": {"question": "What is the capital of Poland?"},
                "expected_output": {"output": "Warsaw"},
                "category": "geography",
            },
        ]
    )

    verifiers.verify_dataset_filtered_items(
        opik_client=opik_client,
        dataset_name=dataset_name,
        filter_string='data.category = "geography"',
        expected_count=2,
        expected_inputs={
            "What is the capital of France?",
            "What is the capital of Poland?",
        },
        project_name=PROJECT_NAME,
    )


def test_get_items_with_filter__filter_excludes_all_items__returns_empty_list(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_items with filter that matches no items returns empty list."""
    DESCRIPTION = "E2E test dataset for empty filter"

    # Create dataset with items
    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
            },
            {
                "input": {"question": "What is 2 + 2?"},
                "expected_output": {"output": "4"},
            },
        ]
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "category": "geography",
            },
            {
                "input": {"question": "What is the capital of Germany?"},
                "category": "geography",
            },
        ]
    )

    verifiers.verify_dataset_filtered_items(
        opik_client=opik_client,
        dataset_name=dataset_name,
        filter_string='data.category = "nonexistent"',
        expected_count=0,
        expected_inputs=set(),
        project_name=PROJECT_NAME,
    )


def _wait_for_version(dataset, expected_version: str, timeout: float = 10) -> None:
    """Wait for dataset to have the expected version, fail if not reached."""
    success = synchronization.until(
        lambda: dataset.get_current_version_name() == expected_version,
        max_try_seconds=timeout,
    )
    assert success, f"Expected version '{expected_version}' was not created in time"


def test_get_version_view__returns_items_from_specific_version(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_version_view returns items from a specific dataset version.

    Also tests that get_current_version_name returns correct version after mutations.
    """
    DESCRIPTION = "E2E test dataset for version view"

    dataset = opik_client.create_dataset(
        dataset_name, description=DESCRIPTION, project_name=PROJECT_NAME
    )

    # Version should be None before any items are inserted
    assert dataset.get_current_version_name() is None

    # Insert first batch of items - creates v1
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "expected_output": {"output": "Paris"},
            },
        ]
    )
    _wait_for_version(dataset, "v1")

    # Insert second batch of items - creates v2
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of Germany?"},
                "expected_output": {"output": "Berlin"},
            },
        ]
    )
    _wait_for_version(dataset, "v2")

    # Get version view for v1 - should only have 1 item
    v1_view = dataset.get_version_view("v1")
    v1_items = v1_view.get_items()
    assert len(v1_items) == 1
    assert v1_items[0]["input"] == {"question": "What is the capital of France?"}
    assert v1_view.version_name == "v1"
    assert v1_view.items_total == 1
    assert v1_view.project_name == PROJECT_NAME

    # Get version view for v2 - should have 2 items
    v2_view = dataset.get_version_view("v2")
    v2_items = v2_view.get_items()
    assert len(v2_items) == 2
    assert v2_view.version_name == "v2"
    assert v2_view.items_total == 2
    assert v2_view.project_name == PROJECT_NAME

    # Current dataset should also have 2 items
    current_items = dataset.get_items()
    assert len(current_items) == 2

    # Delete an item - should create v3
    dataset.delete([current_items[0]["id"]])
    _wait_for_version(dataset, "v3")

    # Get version view for v3 - should have 1 item
    v3_view = dataset.get_version_view("v3")
    v3_items = v3_view.get_items()
    assert len(v3_items) == 1
    assert v3_view.version_name == "v3"
    assert v3_view.items_total == 1
    assert v3_view.project_name == PROJECT_NAME


def test_get_version_view__version_not_found__raises_exception(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that get_version_view raises DatasetVersionNotFound for non-existent version."""
    DESCRIPTION = "E2E test dataset for version not found"

    dataset = opik_client.create_dataset(dataset_name, description=DESCRIPTION)

    # Insert items to create v1
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
            },
        ]
    )
    _wait_for_version(dataset, "v1")

    # Try to get a non-existent version
    with pytest.raises(opik.exceptions.DatasetVersionNotFound):
        dataset.get_version_view("v999")


def test_dataset_items_count__returns_correct_count_after_insert(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that dataset_items_count returns the correct count after insert."""
    dataset = opik_client.create_dataset(dataset_name, description="items_count test")

    dataset.insert(
        [
            {"input": {"question": "What is 2+2?"}},
            {"input": {"question": "What is 3+3?"}},
            {"input": {"question": "What is 4+4?"}},
        ]
    )

    success = synchronization.until(
        lambda: dataset.dataset_items_count == 3,
        max_try_seconds=30,
    )
    assert success, f"Expected dataset_items_count=3, got {dataset.dataset_items_count}"


def _stream_all_items(dataset, **stream_kwargs):
    """Flatten stream_items() into a single list, asserting chunk sizes."""
    chunk_size = stream_kwargs.get("chunk_size", constants.DATASET_STREAM_BATCH_SIZE)
    chunks = list(dataset.stream_items(**stream_kwargs))

    for chunk in chunks[:-1]:
        assert len(chunk) == chunk_size, (
            "Only the last chunk may be shorter than chunk_size"
        )
    for chunk in chunks:
        assert len(chunk) > 0, "Empty chunks must never be yielded"

    return [item for chunk in chunks for item in chunk]


def test_stream_items__small_dataset__returns_inserted_items_with_their_ids(
    opik_client: opik.Opik, dataset_name: str
):
    """Items come back as the inserted data plus an id, and get_items -- which
    is built on this method -- flattens to exactly the same list."""
    dataset = opik_client.create_dataset(
        dataset_name, description="E2E stream_items dataset", project_name=PROJECT_NAME
    )
    inserted = [
        {
            "input": {"question": f"question {i}"},
            "expected_output": {"output": f"answer {i}"},
        }
        for i in range(5)
    ]
    dataset.insert(inserted)

    success = synchronization.until(
        lambda: len(_stream_all_items(dataset)) == 5,
        max_try_seconds=30,
    )
    assert success, "Inserted items did not become readable in time"

    streamed = _stream_all_items(dataset)

    content = [{k: v for k, v in item.items() if k != "id"} for item in streamed]
    assert sorted(content, key=lambda item: item["input"]["question"]) == inserted, (
        "Items must carry the inserted data verbatim"
    )
    assert all(item["id"] for item in streamed)
    assert streamed == dataset.get_items()


def test_stream_items__many_items_and_threads__reads_every_item_exactly_once(
    opik_client: opik.Opik, dataset_name: str
):
    """The threaded, multi-chunk read must not drop, duplicate or reorder items.

    10k items at a 1000-item chunk size is 10 pages, so 8 workers really do fan
    out. Timing is logged rather than asserted: CI runs a single backend
    container, so it is backend-bound and understates the speedup.
    """
    N_ITEMS = 10_000
    CHUNK_SIZE = 1_000

    dataset = opik_client.create_dataset(
        dataset_name,
        description="E2E stream_items parallel dataset",
        project_name=PROJECT_NAME,
    )
    dataset.insert(
        [{"input": {"question": f"question {i}"}} for i in range(N_ITEMS)],
        num_threads=8,
    )

    success = synchronization.until(
        lambda: len(_stream_all_items(dataset, chunk_size=CHUNK_SIZE)) == N_ITEMS,
        max_try_seconds=120,
    )
    assert success, "Inserted items did not become readable in time"

    start = time.perf_counter()
    items = _stream_all_items(dataset, chunk_size=CHUNK_SIZE, num_threads=8)
    elapsed = time.perf_counter() - start
    LOGGER.info(
        "stream_items read %d items in %.2fs (%.0f rows/s)",
        len(items),
        elapsed,
        len(items) / elapsed if elapsed else 0,
    )

    assert len(items) == N_ITEMS
    assert len({item["id"] for item in items}) == N_ITEMS
    assert {item["input"]["question"] for item in items} == {
        f"question {i}" for i in range(N_ITEMS)
    }

    # Same content whatever the thread count, and the order is the backend's
    # page order either way.
    sequential_items = _stream_all_items(dataset, chunk_size=CHUNK_SIZE, num_threads=1)
    assert [item["id"] for item in sequential_items] == [item["id"] for item in items]


def test_stream_items__nb_samples__stops_after_requested_number_of_items(
    opik_client: opik.Opik, dataset_name: str
):
    dataset = opik_client.create_dataset(
        dataset_name,
        description="E2E stream_items nb_samples dataset",
        project_name=PROJECT_NAME,
    )
    dataset.insert([{"input": {"question": f"question {i}"}} for i in range(50)])

    success = synchronization.until(
        lambda: len(_stream_all_items(dataset)) == 50,
        max_try_seconds=30,
    )
    assert success, "Inserted items did not become readable in time"

    items = _stream_all_items(dataset, chunk_size=10, num_threads=4, nb_samples=25)

    assert len(items) == 25
    assert len({item["id"] for item in items}) == 25


def test_stream_items__filter_string__returns_only_matching_items(
    opik_client: opik.Opik, dataset_name: str
):
    dataset = opik_client.create_dataset(
        dataset_name,
        description="E2E stream_items filter dataset",
        project_name=PROJECT_NAME,
    )
    dataset.insert(
        [
            {
                "input": {"question": "What is the capital of France?"},
                "category": "geo",
            },
            {"input": {"question": "What is 2 + 2?"}, "category": "math"},
            {
                "input": {"question": "What is the capital of Poland?"},
                "category": "geo",
            },
        ]
    )

    success = synchronization.until(
        lambda: len(_stream_all_items(dataset)) == 3,
        max_try_seconds=30,
    )
    assert success, "Inserted items did not become readable in time"

    items = _stream_all_items(dataset, filter_string='data.category = "geo"')

    assert len(items) == 2
    assert {item["input"]["question"] for item in items} == {
        "What is the capital of France?",
        "What is the capital of Poland?",
    }


def test_stream_items__dataset_version__reads_that_version_snapshot(
    opik_client: opik.Opik, dataset_name: str
):
    dataset = opik_client.create_dataset(
        dataset_name,
        description="E2E stream_items version dataset",
        project_name=PROJECT_NAME,
    )

    dataset.insert([{"input": {"question": "What is the capital of France?"}}])
    _wait_for_version(dataset, "v1")

    dataset.insert([{"input": {"question": "What is the capital of Germany?"}}])
    _wait_for_version(dataset, "v2")

    v1_items = _stream_all_items(dataset.get_version_view("v1"))
    v2_items = _stream_all_items(dataset.get_version_view("v2"))

    assert len(v1_items) == 1
    assert v1_items[0]["input"] == {"question": "What is the capital of France?"}
    assert len(v2_items) == 2
