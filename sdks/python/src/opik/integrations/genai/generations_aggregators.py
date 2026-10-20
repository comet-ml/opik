from typing import List, Optional

from google.genai import types as genai_types

_TEXT_PART_FIELDS = {"text", "thought_signature"}


def _is_plain_text_part(part: genai_types.Part) -> bool:
    return (
        part.text is not None
        and not part.thought
        and set(part.model_dump(exclude_none=True)) <= _TEXT_PART_FIELDS
    )


def _merge_parts(
    items: List[genai_types.GenerateContentResponse],
) -> List[genai_types.Part]:
    """Concatenate the text parts of the whole stream and keep every other part
    (function calls, inline data, code execution...) in the order it arrived."""
    merged: List[genai_types.Part] = []

    for item in items:
        if not item.candidates or not item.candidates[0].content:
            continue

        for part in item.candidates[0].content.parts or []:
            if part.thought:
                continue

            if _is_plain_text_part(part):
                if merged and _is_plain_text_part(merged[-1]):
                    merged[-1].text += part.text
                    merged[-1].thought_signature = (
                        part.thought_signature or merged[-1].thought_signature
                    )
                    continue
                if part.text == "" and merged:
                    # An empty text part between other parts adds nothing
                    continue
                merged.append(part.model_copy(deep=True))
            else:
                # Function calls, inline data, code execution... are kept whole
                merged.append(part.model_copy(deep=True))

    return merged or [genai_types.Part(text="")]


def aggregate_response_content_items(
    items: List[genai_types.GenerateContentResponse],
) -> genai_types.GenerateContentResponse:
    aggregated = items[-1].model_copy(deep=True)

    if aggregated.usage_metadata is None:
        aggregated.usage_metadata = next(
            (
                item.usage_metadata.model_copy(deep=True)
                for item in reversed(items)
                if item.usage_metadata is not None
            ),
            None,
        )

    last_item_with_candidate: Optional[genai_types.GenerateContentResponse] = next(
        (item for item in reversed(items) if item.candidates), None
    )
    if last_item_with_candidate is not None:
        candidate = last_item_with_candidate.candidates[0].model_copy(deep=True)
        candidate.content = candidate.content or genai_types.Content(role="model")
        candidate.content.parts = _merge_parts(items)
        aggregated.candidates = [candidate]

    return aggregated
