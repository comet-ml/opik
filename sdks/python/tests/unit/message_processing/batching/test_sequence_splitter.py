import dataclasses
from opik.message_processing.batching import sequence_splitter


@dataclasses.dataclass
class LongStr:
    value: str

    def __str__(self) -> str:
        return self.value[1] + ".." + self.value[-1]

    def __repr__(self) -> str:
        return str(self)


ONE_MEGABYTE_OBJECT_A = LongStr("a" * 1024 * 1024)
ONE_MEGABYTE_OBJECT_B = LongStr("b" * 1024 * 1024)
ONE_MEGABYTE_OBJECT_C = LongStr("c" * 1024 * 1024)


def test_split_list_into_batches__by_size_only():
    items = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
    batches = sequence_splitter.split_into_batches(items, max_length=4)

    assert batches == [[1, 2, 3, 4], [5, 6, 7, 8], [9, 10]]


def test_split_list_into_batches__by_memory_only():
    items = [ONE_MEGABYTE_OBJECT_A] * 2 + [ONE_MEGABYTE_OBJECT_B] * 2
    batches = sequence_splitter.split_into_batches(items, max_payload_size_MB=3.5)

    assert batches == [
        [ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_B],
        [ONE_MEGABYTE_OBJECT_B],
    ]


def test_split_list_into_batches__by_memory_and_by_size():
    FOUR_MEGABYTE_OBJECT_C = [ONE_MEGABYTE_OBJECT_C] * 4
    items = (
        [ONE_MEGABYTE_OBJECT_A] * 2
        + [FOUR_MEGABYTE_OBJECT_C]
        + [ONE_MEGABYTE_OBJECT_B] * 2
    )
    batches = sequence_splitter.split_into_batches(
        items, max_length=2, max_payload_size_MB=3.5
    )

    # Object C comes before object A because if item is bigger than the max payload size
    # it is immediately added to the result batches list before batch which is currently accumulating
    assert batches == [
        [FOUR_MEGABYTE_OBJECT_C],
        [ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_A],
        [ONE_MEGABYTE_OBJECT_B, ONE_MEGABYTE_OBJECT_B],
    ]


def test_split_list_into_batches__empty_list():
    items = []
    batches = sequence_splitter.split_into_batches(
        items, max_length=3, max_payload_size_MB=3.5
    )

    assert batches == []


def test_split_list_into_batches__multiple_large_objects():
    items = [ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_B, ONE_MEGABYTE_OBJECT_C]
    batches = sequence_splitter.split_into_batches(
        items, max_length=2, max_payload_size_MB=0.5
    )

    assert batches == [
        [ONE_MEGABYTE_OBJECT_A],
        [ONE_MEGABYTE_OBJECT_B],
        [ONE_MEGABYTE_OBJECT_C],
    ]
