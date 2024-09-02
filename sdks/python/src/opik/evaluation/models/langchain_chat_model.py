from typing import Any

import langchain_openai

from . import base_model


class LangchainChatModel(base_model.OpikBaseModel):
    def __init__(
        self, model_name: str = "gpt-3.5-turbo", **chatopenai_kwargs: Any
    ) -> None:
        super().__init__(model_name=model_name)
        self._engine = langchain_openai.ChatOpenAI(name=model_name, **chatopenai_kwargs)

    def generate(self, input: str) -> str:
        response = self._engine.invoke(input=input)

        return str(response.content)

    async def agenerate(self, input: str) -> str:
        response = await self._engine.ainvoke(input=input)

        return str(response.content)
