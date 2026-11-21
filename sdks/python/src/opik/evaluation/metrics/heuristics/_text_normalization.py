"""Text normalization shared by the string-matching heuristic metrics."""

import unicodedata


def keep_case(text: str) -> str:
    """Return ``text`` in Unicode NFC form, so canonically equivalent strings match.

    "café" written with a precomposed "é" (U+00E9) and with "e" followed by a
    combining acute accent (U+0301) render identically, and both become the same
    string here.
    """
    return unicodedata.normalize("NFC", text)


def fold_case(text: str) -> str:
    """Return ``text`` in a form that ignores both case and Unicode encoding.

    Uses ``str.casefold`` rather than ``str.lower`` so that caseless matching
    works beyond ASCII: for example "STRASSE" and "straße" fold to the same
    string, while ``lower`` keeps them different. NFC is applied before and after
    folding because folding can produce decomposed sequences.
    """
    return keep_case(keep_case(text).casefold())


def normalize(text: str, case_sensitive: bool) -> str:
    return keep_case(text) if case_sensitive else fold_case(text)
