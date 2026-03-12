from datetime import datetime
import uuid
from opik.id_helpers import uuid4_to_uuid7


def test_uuid4_to_uuid7__generates_valid_uuidv7():
    """
    Test that uuid4_to_uuid7 generates valid UUIDv7.
    """
    uuid_v7 = uuid4_to_uuid7(datetime.now(), str(uuid.uuid4()))
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
    uuids_v7 = [str(uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

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
    uuids_v7 = [str(uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

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
    uuids_v7 = [str(uuid4_to_uuid7(ts, uuid4)) for ts, uuid4 in test_uuids]

    # Check ids are different
    assert len(uuids_v7) == len(set(uuids_v7)), "UUIDs are not distinct"
