from typing import List

from google.genai import types as genai_types


def aggregate_response_content_items(
    items: List[genai_types.GenerateContentResponse],
) -> genai_types.GenerateContentResponse:
    full_text = "".join([item.text for item in items if item.text is not None])
    last_item_with_metadata = items[-1].model_copy(deep=True)

    if last_item_with_metadata.candidates:
        first_candidate = last_item_with_metadata.candidates[0]
        first_candidate.content.parts[0].text = full_text
        last_item_with_metadata.candidates = [first_candidate]

    return last_item_with_metadata
