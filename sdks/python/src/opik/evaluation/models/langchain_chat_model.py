from typing import Any

import langchain_openai
from langchain_core.messages import BaseMessage

from . import base_model


class LangchainChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "gpt-3.5-turbo",
        **chatopenai_kwargs: Any,
    ) -> None:
        super().__init__(model_name=model_name)
        self._engine = langchain_openai.ChatOpenAI(name=model_name, **chatopenai_kwargs)

    def generate_string(self, input: str, **kwargs: Any) -> str:
        response = self._engine.invoke(input=input)

        return str(response.content)

    async def agenerate_string(self, input: str, **kwargs: Any) -> str:
        response = await self._engine.ainvoke(input=input)

        return str(response.content)

    def generate_provider_response(self, **kwargs: Any) -> BaseMessage:
        input = kwargs.pop("input")
        return self._engine.invoke(input=input)

    async def agenerate_provider_response(self, **kwargs: Any) -> BaseMessage:
        input = kwargs.pop("input")
        return await self._engine.ainvoke(input=input)
