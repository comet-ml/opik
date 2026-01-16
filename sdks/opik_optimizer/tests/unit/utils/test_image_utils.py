from __future__ import annotations

import base64
from typing import Any, BinaryIO

from opik_optimizer.utils import encode_image_to_base64_uri


def test_encode_image_to_base64_uri_passthrough() -> None:
    payload = "data:image/png;base64,AAAA"
    assert encode_image_to_base64_uri(payload, image_format="PNG") == payload, (
        "Data URI inputs should be returned unchanged."
    )


def test_encode_image_to_base64_uri_bytes() -> None:
    raw = b"arc"
    encoded = encode_image_to_base64_uri(raw, image_format="PNG")
    assert encoded == f"data:image/png;base64,{base64.b64encode(raw).decode('utf-8')}"


def test_encode_image_to_base64_uri_dict_bytes() -> None:
    raw = b"arc2"
    encoded = encode_image_to_base64_uri({"bytes": raw}, image_format="PNG")
    assert encoded == f"data:image/png;base64,{base64.b64encode(raw).decode('utf-8')}"


def test_encode_image_to_base64_uri_saveable_object() -> None:
    class DummyImage:
        def __init__(self, payload: bytes, fmt: str) -> None:
            self._payload = payload
            self.format = fmt

        def save(self, buffer: BinaryIO, **_kwargs: Any) -> None:
            buffer.write(self._payload)

    raw = b"jpeg-bytes"
    encoded = encode_image_to_base64_uri(DummyImage(raw, "JPEG"), image_format="PNG")
    assert encoded == f"data:image/jpeg;base64,{base64.b64encode(raw).decode('utf-8')}"
