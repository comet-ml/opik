from typing import Dict, Any, Optional, List

import os

import litellm
from litellm.integrations.opik.opik import OpikLogger


class OptimizableAgent:
    """
    An agent class to subclass to make an Optimizable Agent.
    """

    model: Optional[str] = None
    project_name: Optional[str] = None
    input_dataset_field: Optional[str] = None

    def __init__(self, agent_config: Dict[str, Any]) -> None:
        if self.project_name is None:
            self.project_name = "Default Project"
        self.init_llm()
        self.init_agent(agent_config)

    def init_llm(self) -> None:
        # Replace if you want to use a different LLM than LiteLLM
        # Litellm bug requires this (maybe problematic if multi-threaded)
        os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def init_agent(self, agent_config: Dict[str, Any]) -> None:
        self.agent_config = agent_config

    @classmethod
    def llm_invoke(
        cls,
        query: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        seed: Optional[int] = None,
    ) -> str:
        # Replace if you want to use a different LLM than LiteLLM
        all_messages = []
        if query:
            all_messages.append({"role": "user", "content": query})

        if messages:
            all_messages.extend(messages)

        response = litellm.completion(
            model=cls.model,
            messages=all_messages,
            seed=seed,
        )
        result = response.choices[0].message.content
        return result

    def invoke(self, query: str) -> str:
        return self.invoke_dataset_item({"question": query})

    def invoke_dataset_item(
        self,
        dataset_item: Dict[str, Any],
        seed: Optional[int] = None,
    ) -> Dict[str, Any]:
        messages = []
        if self.agent_config["chat-prompt"].system:
            messages.append(
                {"role": "system", "content": self.agent_config["chat-prompt"].system}
            )
        if self.agent_config["chat-prompt"].messages:
            messages.extend(self.agent_config["chat-prompt"].messages)

        messages.append(
            {"role": "user", "content": dataset_item[self.input_dataset_field]}
        )

        # Replace with agent invocation:
        result = self.llm_invoke(messages=messages, seed=seed)
        return result
