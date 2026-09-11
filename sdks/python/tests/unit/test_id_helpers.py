from datetime import datetime
import uuid

import pytest

from opik import id_helpers


def test_uuid4_to_uuid7__generates_valid_uuidv7():
    """
    Test that uuid4_to_uuid7 generates valid UUIDv7.
    """
    uuid_v7 = id_helpers.uuid4_to_uuid7(datetime.now(), str(uuid.uuid4()))
    assert uuid_v7.version == 7, f"Generated UUID {uuid_v7} is not a version 7 UUID"


def test_uuid4_to_uuid7__generates_consistent_uuids():
    """
    Test that uuid4_to_uuid7 generates consistent UUIDs.
    """
    # Create test data with known timestamps and UUIDs
    NB_ID = 5
    test_uuids = []

    timestamp = datetime.now()
    uuid4 = str(uuid.uuid4())

    for i in range(NB_ID):
        test_uuids.append((timestamp, uuid4))

    # Convert UUIDs
    uuids_v7 = [str(id_helpers.uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

    # Check ids are distinct
    assert len(set(uuids_v7)) == 1, "UUIDs are not distinct"


def test_uuid4_to_uuid7__sequential_timestamps__maintains_temporal_ordering():
    """
    Test that uuid4_to_uuid7 maintains temporal ordering when given sequential timestamps.
    """
    # Create test data with known timestamps and UUIDs
    NB_ID = 5
    test_uuids = []

    for i in range(NB_ID):
        test_uuids.append((datetime.fromtimestamp(i), str(uuid.uuid4())))

    # Convert UUIDs
    uuids_v7 = [str(id_helpers.uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

    # Assert temporal ordering
    assert uuids_v7 == sorted(uuids_v7), "UUIDs are not sorted"


def test_uuid4_to_uuid7__different_uuid4_same_timestamp():
    """
    Test that uuid4_to_uuid7 creates different UUIDv7 when given the same timestamp and different UUIDv4.
    """
    # Create test data with known timestamps and UUIDs
    NB_ID = 5
    test_uuids = []

    for i in range(NB_ID):
        test_uuids.append((datetime.fromtimestamp(i), str(uuid.uuid4())))

    # Convert UUIDs
    uuids_v7 = [str(id_helpers.uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

    # Check ids are different
    assert len(uuids_v7) == len(set(uuids_v7)), "UUIDs are not distinct"


def test_is_valid_uuid_v7__generated_uuid_v7__returns_true():
    assert id_helpers.is_valid_uuid_v7(id_helpers.generate_id()) is True


def test_is_valid_uuid_v7__uuid4_to_uuid7_output__returns_true():
    converted = id_helpers.uuid4_to_uuid7(datetime.now(), str(uuid.uuid4()))
    assert id_helpers.is_valid_uuid_v7(str(converted)) is True


@pytest.mark.parametrize(
    "value",
    [
        str(uuid.uuid4()),  # v4
        "00000000-0000-0000-0000-000000000000",  # nil UUID (version 0)
        "550e8400-e29b-41d4-a716-446655440000",  # v1-style with version nibble 4
        "6ba7b810-9dad-11d1-80b4-00c04fd430c8",  # v1
    ],
)
def test_is_valid_uuid_v7__non_v7_uuid__returns_false(value):
    assert id_helpers.is_valid_uuid_v7(value) is False


@pytest.mark.parametrize(
    "value",
    [
        "",
        "   ",
        "not-a-uuid",
        "0193b3a5-1234-7abc-9def",  # truncated
        "0193b3a5-1234-7abc-9def-0123456789abXX",  # too long
    ],
)
def test_is_valid_uuid_v7__malformed_string__returns_false(value):
    assert id_helpers.is_valid_uuid_v7(value) is False


@pytest.mark.parametrize(
    "value", [None, 123, 1.5, b"0193b3a5-1234-7abc-9def-0123456789ab", []]
)
def test_is_valid_uuid_v7__non_string_input__returns_false(value):
    assert id_helpers.is_valid_uuid_v7(value) is False


def test_is_valid_uuid_v7__uppercase_v7__returns_true():
    assert id_helpers.is_valid_uuid_v7(id_helpers.generate_id().upper()) is True


def test_is_valid_uuid_v7__urn_prefixed_v7__returns_true():
    assert id_helpers.is_valid_uuid_v7(f"urn:uuid:{id_helpers.generate_id()}") is True
