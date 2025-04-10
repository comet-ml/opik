from typing import Dict
from . import prompt_parameter
import openai


class OpenAIPredictor:
    def __init__(self, model: str, client: openai.OpenAI, **model_kwargs):
        self.model = model
        self._openai_client = client

        self.model_kwargs = model_kwargs

    def predict(
        self,
        prompt_variables: Dict[str, str],
        prompt_parameter: prompt_parameter.PromptParameter,
    ) -> str:
        prompt_template = prompt_parameter.as_prompt_template()
        prompt = prompt_template.format(**prompt_variables)

        response = self._openai_client.chat.completions.create(
            model=self.model,
            messages=[{"role": "user", "content": prompt}],
            **self.model_kwargs
        )

        return response.choices[0].message.content
