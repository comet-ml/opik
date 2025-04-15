import pydantic
from typing import List, Dict
from opik.api_objects.prompt import prompt_template
import opik


class PromptParameter(pydantic.BaseModel):
    name: str
    instruction: str
    few_shot_examples_inputs_from_dataset_columns_mapping: Dict[str, str] = pydantic.Field(default_factory=dict)
    demos: List[Dict[str, str]] = pydantic.Field(default_factory=list)

    def as_prompt_template(
        self, type: opik.PromptType = opik.PromptType.MUSTACHE
    ) -> prompt_template.PromptTemplate:
        # We're just adding examples to the end, but we might want to use special
        # prompt template placeholder for demos, provided by user.

        template_string = self.instruction

        if self.demos:
            template_string += (
                f"\n\nUse the following examples as a guide for how to generate your response."
                f"\n\n{self._render_demos()}"
            )

        template = prompt_template.PromptTemplate(
            template=template_string,
            type=type,
            validate_placeholders=False,
        )
        return template

    def _render_demos(self) -> str:
        rendered_demos: List[str] = []

        for i, demo in enumerate(self.demos, 1):
            example_lines = [f"**Example {i}**"]
            for display_key, dataset_column_key in self.few_shot_examples_inputs_from_dataset_columns_mapping.items():
                example_lines.append(f"  {display_key}: '{demo[dataset_column_key]}'")

            rendered_demos.append("\n".join(example_lines))

        return "\n\n".join(rendered_demos)
