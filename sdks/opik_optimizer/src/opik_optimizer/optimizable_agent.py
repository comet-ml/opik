from typing import Dict, Any

import os

import litellm
from litellm.integrations.opik.opik import OpikLogger

from .optimization_config.chat_prompt import ChatPrompt


class OptimizableAgent:
    """
    An agent class to subclass to make an Optimizable Agent.
    """

    model = None
    project_name = None
    fallback_prompt = "You are a helpful assistant."

    def __init__(self, agent_config: Dict[str, Any]) -> None:
        if self.project_name is None:
            self.project_name = "Default Project"
        self.init_llm()
        self.init_agent(agent_config)

    def init_llm(self) -> None:
        # Litellm bug requires this (maybe problematic if multi-threaded)
        os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def init_agent(self, agent_config: Dict[str, Any]) -> None:
        self.chat_prompt = agent_config.get(
            "chat-prompt", ChatPrompt(system=self.fallback_prompt)
        )

    def llm_invoke(self, query: str) -> str:
        messages = []
        if self.chat_prompt.system:
            messages.append({"role": "system", "content": self.chat_prompt.system})
        if self.chat_prompt.messages:
            messages.extend(self.chat_prompt.messages)

        messages.append({"role": "user", "content": query})

        response = litellm.completion(
            model=self.model,
            messages=messages,
        )
        result = response.choices[0].message.content
        return result

    def invoke(self, query: str) -> str:
        # Replace with agent invocation:
        return self.llm_invoke(query)

    def invoke_dataset_item(
        self, dataset_item: Dict[str, Any], dataset_input_field: str
    ) -> Dict[str, Any]:
        # Replace with agent invocation:
        result = self.llm_invoke(dataset_item[dataset_input_field])
        return {"output": result}
