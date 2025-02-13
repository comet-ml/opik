from datetime import datetime
from typing import Optional
import uuid
import uuid6


def generate_id(timestamp: Optional[datetime] = None) -> str:
    if timestamp:
        uuid4 = str(uuid.uuid4())
        return str(uuid4_to_uuid7(timestamp, uuid4))

    return str(uuid6.uuid7())


def uuid4_to_uuid7(user_datetime: datetime, user_uuid: str) -> uuid.UUID:
    """Convert a UUID v4 into a UUID v7 following RFC draft specification."""
    # Get Unix timestamp in milliseconds
    unix_ts_ms = int(user_datetime.timestamp() * 1000)

    uuidv4 = uuid.UUID(user_uuid)
    if uuidv4.version != 4:
        raise ValueError("Input UUID must be version 4")

    # Create the 16-byte array
    uuid_bytes = bytearray(16)

    # First 48 bits (6 bytes): Unix timestamp in milliseconds
    uuid_bytes[0:6] = unix_ts_ms.to_bytes(6, byteorder="big")

    # Next byte: Version 7 in top 4 bits
    uuid_bytes[6] = 0x70 | (uuidv4.bytes[6] & 0x0F)

    # Next byte: random from UUID v4
    uuid_bytes[7] = uuidv4.bytes[7]

    # Next byte: Variant bits (0b10) in top 2 bits
    uuid_bytes[8] = 0x80 | (uuidv4.bytes[8] & 0x3F)

    # Remaining bytes: random from UUID v4
    uuid_bytes[9:16] = uuidv4.bytes[9:16]

    return uuid.UUID(bytes=bytes(uuid_bytes))
