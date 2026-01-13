from typing import Any

import logging
import opik
from pydantic import BaseModel

from .. import reporting
from .... import _llm_calls
from ....utils.prompt_library import PromptLibrary

logger = logging.getLogger(__name__)


class StyleInferenceResponse(BaseModel):
    style: str


def infer_output_style_from_dataset(
    dataset: opik.Dataset,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    n_examples: int = 5,
    verbose: int = 1,
    *,
    raise_on_failure: bool = False,
) -> str | None:
    """Analyzes dataset examples to infer the desired output style using the LLM."""
    with reporting.infer_output_style(verbose=verbose) as report_infer_output_style:
        report_infer_output_style.start_style_inference()

        try:
            items_to_process = dataset.get_items(n_examples)
        except Exception as e:
            report_infer_output_style.error(
                f"Failed to get items from dataset '{dataset.name}': {e}"
            )
            return None

        if not items_to_process:
            report_infer_output_style.error(
                f"Dataset '{dataset.name}' is empty. Cannot infer output style."
            )
            return None

        if len(items_to_process) < min(n_examples, 2):
            report_infer_output_style.error(
                f"Not enough dataset items (found {len(items_to_process)}) to reliably infer output style. Need at least {min(n_examples, 2)}."
            )
            return None

        examples_str = ""
        for i, item_content in enumerate(items_to_process):
            filtered_content: dict[str, str] = {
                x: y for x, y in item_content.items() if x != "id"
            }
            examples_str += (
                f"Example {i + 1}:\nDataset Item:\n{filtered_content}\n---\n"
            )

        user_prompt_for_style_inference = prompts.get(
            "style_inference_user_prompt_template", examples_str=examples_str
        )

        try:
            response = _llm_calls.call_model(
                messages=[
                    {
                        "role": "system",
                        "content": prompts.get("infer_style_system_prompt"),
                    },
                    {"role": "user", "content": user_prompt_for_style_inference},
                ],
                model=model,
                model_parameters=model_parameters,
                is_reasoning=True,
                response_model=StyleInferenceResponse,
                return_all=_llm_calls.requested_multiple_candidates(model_parameters),
            )
            inferred_style_response = (
                response[0] if isinstance(response, list) else response
            )
            inferred_style = inferred_style_response.style.strip()
            if inferred_style:
                report_infer_output_style.success(inferred_style)
                return inferred_style
            report_infer_output_style.error(
                "LLM returned empty string for inferred output style."
            )
            return None
        except Exception as e:
            report_infer_output_style.error(f"Error during output style inference: {e}")
            if raise_on_failure:
                raise
            return None
