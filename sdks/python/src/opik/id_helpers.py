from datetime import datetime
import uuid


# STATIC NS
UUIDV7_NS = 1733244176020523256


def uuid4_to_uuid7(user_datetime: datetime, user_uuid: str) -> uuid.UUID:
    """Convert an UUID4 in a UUID7 in a naive way"""
    last = [0, 0, 0, 0]

    # Use a hardcoded timestamp in ns for now
    ns = UUIDV7_NS

    # Simple uuid7 implementation
    sixteen_secs = 16_000_000_000
    t1, rest1 = divmod(ns, sixteen_secs)
    t2, rest2 = divmod(rest1 << 16, sixteen_secs)
    t3, _ = divmod(rest2 << 12, sixteen_secs)
    t3 |= 7 << 12  # Put uuid version in top 4 bits, which are 0 in t3

    # The next two bytes are an int (t4) with two bits for
    # the variant 2 and a 14 bit sequence counter which increments
    # if the time is unchanged.
    if t1 == last[0] and t2 == last[1] and t3 == last[2]:
        # Stop the seq counter wrapping past 0x3FFF.
        # This won't happen in practice, but if it does,
        # uuids after the 16383rd with that same timestamp
        # will not longer be correctly ordered but
        # are still unique due to the 6 random bytes.
        if last[3] < 0x3FFF:
            last[3] += 1
    else:
        last[:] = (t1, t2, t3, 0)
    t4 = (2 << 14) | last[3]  # Put variant 0b10 in top two bits

    # Six random bytes from the provided UUIDv4
    uuidv4 = uuid.UUID(user_uuid)
    assert uuidv4.version == 4
    rand = uuidv4.bytes[-6:]

    return uuid.UUID(f"{t1:>08x}-{t2:>04x}-{t3:>04x}-{t4:>04x}-{rand.hex()}")
