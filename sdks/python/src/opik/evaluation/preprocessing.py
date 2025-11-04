"""Shared text preprocessing utilities for metrics."""

from __future__ import annotations

import re
import string
import unicodedata
from typing import Callable, Literal

try:  # optional dependency for emoji detection
    import emoji
except ImportError:  # pragma: no cover
    emoji = None  # type: ignore

_Normalizer = Callable[[str], str]


def normalize_text(
    text: str,
    *,
    lowercase: bool = True,
    strip_accents: bool = False,
    remove_punctuation: bool = False,
    keep_emoji: bool = True,
    normalize_form: Literal["NFC", "NFD", "NFKC", "NFKD"] = "NFKC",
) -> str:
    """Normalize text before metric processing.

    Args:
        text: Input string.
        lowercase: Whether to lowercase the text.
        strip_accents: Remove diacritical marks.
        remove_punctuation: Strip ASCII punctuation.
        keep_emoji: Preserve emoji characters; if False they are removed.
        normalize_form: Unicode normalization form to apply (default NFKC).
    """

    normalized = unicodedata.normalize(normalize_form, text)
    if lowercase:
        normalized = normalized.lower()

    if not keep_emoji:
        normalized = _remove_emoji(normalized)

    if strip_accents:
        normalized = _strip_accents(normalized)

    if remove_punctuation:
        normalized = _remove_punctuation(normalized)

    normalized = _collapse_whitespace(normalized)
    return normalized.strip()


def _remove_emoji(text: str) -> str:
    if emoji is None:  # pragma: no cover
        return "".join(
            ch for ch in text if unicodedata.category(ch) not in {"So", "Sk"}
        )
    return emoji.replace_emoji(text, replace="")


def _strip_accents(text: str) -> str:
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def _remove_punctuation(text: str) -> str:
    translator = str.maketrans("", "", string.punctuation)
    stripped = text.translate(translator)
    return re.sub(
        r"[\u2010-\u2015\u2018-\u201f\u2020-\u2027\u2030-\u2043]", "", stripped
    )


def _collapse_whitespace(text: str) -> str:
    return re.sub(r"\s+", " ", text)


DEFAULT_NORMALIZER: _Normalizer = normalize_text


def ascii_normalizer(text: str) -> str:
    return normalize_text(
        text,
        strip_accents=True,
        remove_punctuation=True,
        keep_emoji=False,
    )


ASCII_NORMALIZER: _Normalizer = ascii_normalizer
