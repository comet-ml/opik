from typing import List

from google.genai import types as genai_types


def aggregate_response_content_items(
    items: List[genai_types.GenerateContentResponse],
) -> genai_types.GenerateContentResponse:
    # Handle empty items list by returning an empty response
    if not items:
        return genai_types.GenerateContentResponse()

    full_text = "".join([item.text for item in items if item and item.text is not None])

    # Get a copy of the last item to preserve metadata
    last_item_with_metadata = items[-1].model_copy(deep=True)

    # Update the text in the first candidate's first part, if they exist
    if (
        last_item_with_metadata.candidates
        and last_item_with_metadata.candidates[0]
        and last_item_with_metadata.candidates[0].content
        and last_item_with_metadata.candidates[0].content.parts
        and last_item_with_metadata.candidates[0].content.parts[0]
    ):
        first_candidate = last_item_with_metadata.candidates[0]
        first_candidate.content.parts[0].text = full_text
        # Replace candidates list with just the modified first candidate
        last_item_with_metadata.candidates = [first_candidate]
    # If candidates or their structure is missing, the original candidates list is kept

    return last_item_with_metadata
