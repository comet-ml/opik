import pydantic
import json
from typing import List, Dict
from opik.api_objects.prompt import prompt_template
import opik

class PromptParameter(pydantic.BaseModel):
    name: str
    instruction: str
    demos: List[Dict[str, str]] = pydantic.Field(default_factory=list)

    def as_prompt_template(self, type: opik.PromptType = opik.PromptType.MUSTACHE) -> prompt_template.PromptTemplate:
        template_string = f"""
        {self.instruction}.
        
        Few-shot examples demonstrating the inputs and expected outputs:
        {self._render_demos()}
        """

        template = prompt_template.PromptTemplate(
            template=template_string,
            type=type,
            validate_placeholders=False,
        )
        return template
    
    def _render_demos(self) -> str:
        return "\n".join([json.dumps(demo, indent=4) for demo in self.demos])
