import json

import pytest

from opik.message_processing.batching import sequence_splitter

from ....testlib import fake_message_factory


ONE_MEGABYTE_OBJECT_A = fake_message_factory.LongStr(
    "a" * fake_message_factory.ONE_MEGABYTE
)
ONE_MEGABYTE_OBJECT_B = fake_message_factory.LongStr(
    "b" * fake_message_factory.ONE_MEGABYTE
)
ONE_MEGABYTE_OBJECT_C = fake_message_factory.LongStr(
    "c" * fake_message_factory.ONE_MEGABYTE
)


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

    assert batches == [
        [ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_A],
        [FOUR_MEGABYTE_OBJECT_C],
        [ONE_MEGABYTE_OBJECT_B, ONE_MEGABYTE_OBJECT_B],
    ]


def test_split_list_into_batches__oversized_item__flushes_what_is_already_accumulating_first():
    FOUR_MEGABYTE_OBJECT_C = [ONE_MEGABYTE_OBJECT_C] * 4
    items = [ONE_MEGABYTE_OBJECT_A, FOUR_MEGABYTE_OBJECT_C, ONE_MEGABYTE_OBJECT_B]
    batches = sequence_splitter.split_into_batches(items, max_payload_size_MB=3.5)

    assert batches == [
        [ONE_MEGABYTE_OBJECT_A],
        [FOUR_MEGABYTE_OBJECT_C],
        [ONE_MEGABYTE_OBJECT_B],
    ]


def test_split_list_into_batches__oversized_item_after_an_earlier_batch__still_keeps_order():
    FOUR_MEGABYTE_OBJECT_C = [ONE_MEGABYTE_OBJECT_C] * 4
    items = [ONE_MEGABYTE_OBJECT_A, ONE_MEGABYTE_OBJECT_B, FOUR_MEGABYTE_OBJECT_C]
    batches = sequence_splitter.split_into_batches(
        items, max_length=1, max_payload_size_MB=3.5
    )

    assert batches == [
        [ONE_MEGABYTE_OBJECT_A],
        [ONE_MEGABYTE_OBJECT_B],
        [FOUR_MEGABYTE_OBJECT_C],
    ]


@pytest.mark.parametrize("max_length", [1, 2, 3, 1000])
def test_split_list_into_batches__oversized_items_anywhere__batches_flatten_back_to_the_input(
    max_length,
):
    FOUR_MEGABYTE_OBJECT_C = [ONE_MEGABYTE_OBJECT_C] * 4
    items = [
        ONE_MEGABYTE_OBJECT_A,
        FOUR_MEGABYTE_OBJECT_C,
        ONE_MEGABYTE_OBJECT_B,
        ONE_MEGABYTE_OBJECT_A,
        FOUR_MEGABYTE_OBJECT_C,
        FOUR_MEGABYTE_OBJECT_C,
        ONE_MEGABYTE_OBJECT_B,
    ]
    batches = sequence_splitter.split_into_batches(
        items, max_length=max_length, max_payload_size_MB=3.5
    )

    assert [item for batch in batches for item in batch] == items


def test_split_list_into_batches__batch_after_an_oversized_item__still_pays_for_its_brackets():
    # A 20-byte budget holds three quoted "ab" plus the brackets and commas around
    # them, and a fourth only if the brackets go uncharged. The batch opened after an
    # oversized item owes them just like the batch the run starts with. The leading
    # "ab" is what puts a partial batch in front of the oversized item.
    TWENTY_BYTES_MB = 20 / fake_message_factory.ONE_MEGABYTE
    items = ["ab", "b" * 20] + ["ab"] * 6

    batches = sequence_splitter.split_into_batches(
        items, max_payload_size_MB=TWENTY_BYTES_MB
    )

    assert [len(batch) for batch in batches] == [1, 1, 3, 3]
    for batch in batches[2:]:
        serialized = json.dumps(batch, separators=(",", ":")).encode("utf-8")
        assert len(serialized) <= 20


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


@pytest.mark.parametrize(
    "test_input",
    [
        ("string"),  # Basic string
        (""),  # Empty string
        ("Unicode👋🏼"),  # String with non-UTF8 characters
        (123.76),  # Float
        (123456789),  # Integer
        (True),  # Boolean
        (False),  # Boolean
        ([1, 2, 3]),  # List
        ({"key": "value"}),  # Dictionary
        (None),  # null
    ],
)
def test_get_json_size_basic(test_input):
    expected = len(
        json.dumps(test_input, separators=(",", ":"), ensure_ascii=False).encode(
            "utf-8"
        )
    )
    assert sequence_splitter._get_json_size(test_input) == expected


def test_get_json_size_complex_nested():
    test_input = {
        "string": "simple value",
        "number": 42,
        "boolean": True,
        "null": None,
        "nested_dict": {
            "level1": {
                "level2": {
                    "level3": "deep value",
                    "numbers": [1, 2, 3],
                    "mixed_list": [True, "string", 42.0, None],
                },
                "sibling2": {
                    "data": [{"id": 1, "name": "item1"}, {"id": 2, "name": "item2"}]
                },
            }
        },
        "array_of_arrays": [[1, 2, 3], ["a", "b", "c"], [{"x": 1}, {"y": 2}]],
        "mixed_nesting": {
            "lists": [[1, 2], [3, 4]],
            "dicts": {"a": {"b": {"c": "value"}}, "x": {"y": {"z": 100}}},
        },
    }

    expected = len(
        json.dumps(test_input, separators=(",", ":"), ensure_ascii=False).encode(
            "utf-8"
        )
    )
    assert sequence_splitter._get_json_size(test_input) == expected


def test_split_list_into_batches__items_packed_to_the_limit__serialized_batch_stays_under_it():
    # A kilobyte per item once quoted, so 1024 of them pack to exactly the 1MB
    # budget and the commas between them are all that pushes the request over.
    ONE_MEGABYTE = fake_message_factory.ONE_MEGABYTE
    items = ["a" * 1022] * 1024

    batches = sequence_splitter.split_into_batches(items, max_payload_size_MB=1.0)

    assert [len(batch) for batch in batches] == [1023, 1]
    for batch in batches:
        serialized = json.dumps(batch, separators=(",", ":")).encode("utf-8")
        assert len(serialized) <= ONE_MEGABYTE
