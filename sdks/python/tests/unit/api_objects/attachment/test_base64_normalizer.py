import base64
import uuid

import pytest

from opik.api_objects.attachment import base64_normalizer
from . import constants


def _to_urlsafe(standard_b64: str) -> str:
    """Re-encode a standard-base64 string into URL-safe form for fixtures."""
    return base64.urlsafe_b64encode(base64.b64decode(standard_b64)).decode("utf-8")


# Bytes whose base64 encoding contains '+' / '/' (so URL-safe encoding contains
# '-' / '_'). Appended to fixtures to guarantee we actually exercise the
# URL-safe path rather than falling through the "no '-'/'_'" early exit.
_URLSAFE_FORCING_TAIL = b"\xfb\xff\xfe" * 8


def _urlsafe_image_fixture(signature: bytes) -> str:
    """Build a URL-safe base64 string that (a) decodes to bytes starting with
    ``signature`` and (b) is guaranteed to contain '-' or '_' so the
    detector's URL-safe-alphabet pre-check actually runs.
    """
    encoded = base64.urlsafe_b64encode(signature + _URLSAFE_FORCING_TAIL).decode(
        "utf-8"
    )
    assert "-" in encoded or "_" in encoded, "fixture failed to force URL-safe chars"
    return encoded


# ---------------------------------------------------------------------------
# urlsafe_to_standard_base64
# ---------------------------------------------------------------------------


class TestUrlsafeToStandardBase64:
    def test_replaces_dash_and_underscore(self):
        assert (
            base64_normalizer.urlsafe_to_standard_base64("ab-cd_ef==") == "ab+cd/ef=="
        )

    def test_noop_when_already_standard(self):
        value = "iVBORw0KGgo+AB/CD=="
        assert base64_normalizer.urlsafe_to_standard_base64(value) == value

    def test_preserves_padding(self):
        assert base64_normalizer.urlsafe_to_standard_base64("ab-_==") == "ab+/=="

    def test_empty_string(self):
        assert base64_normalizer.urlsafe_to_standard_base64("") == ""


# ---------------------------------------------------------------------------
# is_urlsafe_base64_image
# ---------------------------------------------------------------------------


class TestIsUrlsafeBase64Image:
    @pytest.mark.parametrize(
        "signature",
        [
            b"\x89PNG\r\n\x1a\n",
            b"\xff\xd8\xff",
            b"GIF87a",
            b"GIF89a",
            b"RIFF\x00\x00\x00\x00WEBP",
            b"II*\x00",
            b"MM\x00*",
        ],
        ids=["png", "jpeg", "gif87a", "gif89a", "webp", "tiff-le", "tiff-be"],
    )
    def test_detects_urlsafe_encoded_image(self, signature):
        """Every supported image signature, when URL-safe-base64-encoded, is detected."""
        urlsafe = _urlsafe_image_fixture(signature)
        assert base64_normalizer.is_urlsafe_base64_image(urlsafe) is True

    def test_detects_urlsafe_encoded_real_png(self):
        """End-to-end sanity check using a real PNG fixture from constants.py."""
        urlsafe = _to_urlsafe(constants.PNG_BASE64)
        # The real PNG fixture happens to contain '+' / '/' in its standard
        # encoding, so its URL-safe form contains '-' / '_' — exercise that.
        assert "-" in urlsafe or "_" in urlsafe
        assert base64_normalizer.is_urlsafe_base64_image(urlsafe) is True

    def test_rejects_standard_base64_image(self):
        """Standard-alphabet base64 should short-circuit to False — nothing to rewrite."""
        assert base64_normalizer.is_urlsafe_base64_image(constants.PNG_BASE64) is False

    def test_rejects_short_string(self):
        assert base64_normalizer.is_urlsafe_base64_image("ab-_cd==") is False

    def test_rejects_uuid_like_string(self):
        """UUIDs share the URL-safe alphabet and contain '-', but aren't images."""
        for _ in range(5):
            assert base64_normalizer.is_urlsafe_base64_image(str(uuid.uuid4())) is False

    def test_rejects_urlsafe_non_image_payload(self):
        """Non-image binary in URL-safe base64 (e.g. PDF) must be left alone."""
        urlsafe_pdf = _to_urlsafe(constants.PDF_BASE64)
        assert base64_normalizer.is_urlsafe_base64_image(urlsafe_pdf) is False

    def test_rejects_text_with_dashes(self):
        text = "hello-world this is not base64-encoded at all"
        assert base64_normalizer.is_urlsafe_base64_image(text) is False

    def test_rejects_invalid_base64_alphabet(self):
        # Contains '$' which is not in either alphabet
        assert (
            base64_normalizer.is_urlsafe_base64_image("iVBORw0KGgo$$$abcdefgh") is False
        )

    def test_rejects_empty_string(self):
        assert base64_normalizer.is_urlsafe_base64_image("") is False


# ---------------------------------------------------------------------------
# normalize_urlsafe_base64_images_in_place
# ---------------------------------------------------------------------------


class TestNormalizeUrlsafeBase64ImagesInPlace:
    def test_rewrites_image_string_in_top_level_dict(self):
        urlsafe = _to_urlsafe(constants.PNG_BASE64)
        payload = {"data": urlsafe}

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert "-" not in payload["data"]
        assert "_" not in payload["data"]
        # Round-trips back to the original PNG bytes
        original_bytes = base64.b64decode(constants.PNG_BASE64)
        assert base64.b64decode(payload["data"]) == original_bytes

    def test_rewrites_image_string_in_list(self):
        urlsafe = _to_urlsafe(constants.JPEG_BASE64)
        payload = ["leading text", urlsafe]

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert payload[0] == "leading text"
        assert "-" not in payload[1] and "_" not in payload[1]

    def test_rewrites_deeply_nested_image(self):
        urlsafe = _to_urlsafe(constants.PNG_BASE64)
        payload = {
            "role": "user",
            "parts": [
                {"text": "hello"},
                {"inline_data": {"data": urlsafe, "mime_type": "image/png"}},
            ],
        }

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        normalized = payload["parts"][1]["inline_data"]["data"]
        assert "-" not in normalized and "_" not in normalized
        assert base64.b64decode(normalized) == base64.b64decode(constants.PNG_BASE64)

    def test_leaves_uuid_values_untouched(self):
        uid = str(uuid.uuid4())
        payload = {"trace_id": uid, "nested": [{"span_id": uid}]}

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert payload["trace_id"] == uid
        assert payload["nested"][0]["span_id"] == uid

    def test_leaves_standard_base64_image_untouched(self):
        """If the value is already standard-alphabet base64, it stays byte-for-byte identical."""
        payload = {"data": constants.PNG_BASE64}

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert payload["data"] == constants.PNG_BASE64

    def test_leaves_non_image_binary_untouched(self):
        """URL-safe non-image binary (e.g. PDF) is not rewritten — only images are."""
        urlsafe_pdf = _to_urlsafe(constants.PDF_BASE64)
        payload = {"data": urlsafe_pdf}

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert payload["data"] == urlsafe_pdf

    def test_ignores_non_string_leaves(self):
        payload = {"a": 1, "b": None, "c": True, "d": 1.5, "e": [None, 2, False]}
        snapshot = {"a": 1, "b": None, "c": True, "d": 1.5, "e": [None, 2, False]}

        base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert payload == snapshot

    def test_returns_none_and_mutates_in_place(self):
        urlsafe = _to_urlsafe(constants.PNG_BASE64)
        payload = {"data": urlsafe}

        result = base64_normalizer.normalize_urlsafe_base64_images_in_place(payload)

        assert result is None
        assert payload["data"] != urlsafe  # was mutated

    def test_handles_empty_structures(self):
        empty_dict: dict = {}
        empty_list: list = []

        base64_normalizer.normalize_urlsafe_base64_images_in_place(empty_dict)
        base64_normalizer.normalize_urlsafe_base64_images_in_place(empty_list)

        assert empty_dict == {}
        assert empty_list == []

    def test_handles_non_container_root(self):
        """Scalar roots are valid inputs (no-op) — the walker should not raise."""
        base64_normalizer.normalize_urlsafe_base64_images_in_place("plain string")
        base64_normalizer.normalize_urlsafe_base64_images_in_place(42)
        base64_normalizer.normalize_urlsafe_base64_images_in_place(None)
