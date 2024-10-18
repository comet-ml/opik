import logging
from typing import Any, Dict, List

import litellm
from litellm.types.utils import ModelResponse

from . import base_model

LOGGER = logging.getLogger(__name__)


class LiteLLLMChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "gpt-3.5-turbo",
        **kwargs: Any,
    ) -> None:
        super().__init__(model_name=model_name)

        self._model_kwargs: Dict[str, Any] = kwargs
        self._model_kwargs = self.cleanup_params(self._model_kwargs)

        self._engine = litellm

    def cleanup_params(self, params: Dict[str, Any]) -> Dict[str, Any]:
        supported_params = set(
            litellm.get_supported_openai_params(
                model=self.model_name,
            )
        )

        result = {}

        for key in params:
            if key not in supported_params:
                LOGGER.warning(f"Unsupported parameter: '{key}' will be ignored.")
            else:
                result[key] = params[key]

        return result

    def generate(self, input: List, **kwargs: Any) -> str:
        response = self.generate_ext(input=input, **kwargs)
        return response.choices[0].message.content

    def generate_ext(self, input: List, **kwargs: Any) -> ModelResponse:
        self.cleanup_params(kwargs)
        all_kwargs = {**self._model_kwargs, **kwargs}

        response = self._engine.completion(
            model=self.model_name, messages=input, **all_kwargs
        )

        return response

    async def agenerate(self, input: List, **kwargs: Any) -> str:
        response = await self.agenerate_ext(input=input, **kwargs)
        return response.choices[0].message.content

    async def agenerate_ext(self, input: List, **kwargs: Any) -> ModelResponse:
        self.cleanup_params(kwargs)
        all_kwargs = {**self._model_kwargs, **kwargs}

        response = await self._engine.acompletion(
            model=self.model_name, messages=input, **all_kwargs
        )

        return response
