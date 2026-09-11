"""Bridge command HMAC signing and verification using the HKDF-derived bridge key."""

import base64
import hashlib
import hmac
import json


def sign(bridge_key: bytes, command_type: str, args: dict) -> str:
    """HMAC-SHA256(bridge_key, type || args_json) → base64."""
    msg = command_type.encode("utf-8") + json.dumps(
        args, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    sig = hmac.new(bridge_key, msg, hashlib.sha256).digest()
    return base64.b64encode(sig).decode("ascii")


def verify(
    bridge_key: bytes, command_type: str, args: dict, expected_hmac: str
) -> bool:
    """Verify HMAC on a bridge command."""
    if not expected_hmac:
        return False
    expected = sign(bridge_key, command_type, args)
    return hmac.compare_digest(expected, expected_hmac)
