from typing import Optional, Dict

import logging

import opik

from . import prompts as evo_prompts
from . import reporting
from ..optimization_config import chat_prompt


logger = logging.getLogger(__name__)


class StyleOps:
    def _infer_output_style_from_dataset(
        self, dataset: opik.Dataset, prompt: chat_prompt.ChatPrompt, n_examples: int = 5
    ) -> Optional[str]:
        """Analyzes dataset examples to infer the desired output style using the LLM."""
        with reporting.infer_output_style(
            verbose=self.verbose
        ) as report_infer_output_style:
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
                    f"Not enough dataset items (found {len(items_to_process)}) to reliably infer output style. Need at least {min(n_examples,2)}."
                )
                return None

            examples_str = ""
            for i, item_content in enumerate(items_to_process):
                filtered_content: Dict[str, str] = {
                    x: y for x, y in item_content.items() if x != "id"
                }
                examples_str += (
                    f"Example {i+1}:\nDataset Item:\n{filtered_content}\n---\n"
                )

            user_prompt_for_style_inference = evo_prompts.style_inference_user_prompt(
                examples_str
            )

            try:
                inferred_style = self._call_model(
                    messages=[
                        {
                            "role": "system",
                            "content": evo_prompts.INFER_STYLE_SYSTEM_PROMPT,
                        },
                        {"role": "user", "content": user_prompt_for_style_inference},
                    ],
                    is_reasoning=True,
                )
                inferred_style = inferred_style.strip()
                if inferred_style:
                    report_infer_output_style.success(inferred_style)
                    return inferred_style
                else:
                    report_infer_output_style.error(
                        "LLM returned empty string for inferred output style."
                    )
                    return None
            except Exception as e:
                report_infer_output_style.error(
                    f"Error during output style inference: {e}"
                )
                return None
