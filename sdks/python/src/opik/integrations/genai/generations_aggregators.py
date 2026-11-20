from typing import List

from google.genai import types as genai_types


def _is_plain_text_part(part: genai_types.Part) -> bool:
    """A part is mergeable text only when ``text`` is its sole set field.

    Any part carrying another payload (a function call, inline data, a
    thought signature, ...) must stay a separate part, otherwise merging
    would silently drop that payload.
    """
    return set(part.model_dump(exclude_none=True).keys()) == {"text"}


def _parts_of(
    response: genai_types.GenerateContentResponse,
) -> List[genai_types.Part]:
    if not response.candidates:
        return []
    content = response.candidates[0].content
    if content is None:
        return []
    return content.parts or []


def aggregate_response_content_items(
    items: List[genai_types.GenerateContentResponse],
) -> genai_types.GenerateContentResponse:
    """Fold streamed ``GenerateContentResponse`` chunks into one response.

    Every chunk's first candidate is walked in arrival order: consecutive
    plain-text parts are concatenated into a single part, while every other
    part (function calls, inline data, ...) is kept as its own part. The
    previous implementation joined ``item.text`` across chunks and wrote the
    result into the last chunk's first part, which dropped function calls
    from earlier chunks (their ``.text`` is ``None``) and overwrote a
    function-call part in the last chunk with plain text.

    Metadata (model version, usage, ...) comes from the last chunk, as
    before; a trailing chunk without candidates (usage-only) no longer
    discards the parts aggregated from earlier chunks.
    """
    if not items:
        return genai_types.GenerateContentResponse()

    merged_parts: List[genai_types.Part] = []
    for item in items:
        for part in _parts_of(item):
            part_copy = part.model_copy(deep=True)
            if (
                _is_plain_text_part(part_copy)
                and merged_parts
                and _is_plain_text_part(merged_parts[-1])
            ):
                merged_parts[-1].text = (merged_parts[-1].text or "") + (
                    part_copy.text or ""
                )
            else:
                merged_parts.append(part_copy)

    aggregated = items[-1].model_copy(deep=True)
    if not aggregated.candidates:
        # A usage-only trailing chunk carries no candidate to hold the
        # parts; borrow the candidate shape from the last chunk that has
        # one, keeping the trailing chunk's response-level metadata.
        for item in reversed(items):
            if item.candidates:
                aggregated.candidates = [item.candidates[0].model_copy(deep=True)]
                break
    if aggregated.candidates:
        first_candidate = aggregated.candidates[0]
        if first_candidate.content is None:
            first_candidate.content = genai_types.Content(
                role="model", parts=merged_parts
            )
        else:
            first_candidate.content.parts = merged_parts
        aggregated.candidates = [first_candidate]

    return aggregated
