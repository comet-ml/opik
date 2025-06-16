from typing import Dict, Any, Optional, List
from pydantic import BaseModel, Field

import os
import copy

import litellm
from litellm.integrations.opik.opik import OpikLogger

from . import _throttle
from .optimization_config.chat_prompt import ChatPrompt

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class AgentConfig(BaseModel):
    """Configuration model for the agent"""

    chat_prompt: Optional[ChatPrompt] = Field(
        default=None, description="The chat prompt configuration for the agent"
    )
    tools: Optional[Dict[str, Any]] = Field(
        default=None, description="Optional dictionary of tools available to the agent"
    )

    class Config:
        arbitrary_types_allowed = True  # Allow ChatPrompt type

    def get_formatted_messages(self) -> List[Dict[str, str]]:
        if self.chat_prompt is not None:
            return self.chat_prompt.formatted_messages
        else:
            return []

    def get_system_prompt(self) -> str:
        if self.chat_prompt is not None:
            return self.chat_prompt.get_system_prompt()
        else:
            return ""

    def copy(self, **kwargs: Any) -> "AgentConfig":
        """
        Create a deep copy of this AgentConfig instance.

        Args:
            **kwargs: Additional arguments to pass to the BaseModel.copy() method

        Returns:
            AgentConfig: A new instance with deep copied chat_prompt and tools
        """
        # Create a copy of the base model
        base_copy = super().copy(**kwargs)

        # Create a new ChatPrompt instance with deep copied messages if it exists
        if self.chat_prompt:
            # Deep copy the messages list and its contents if it exists
            messages = (
                copy.deepcopy(self.chat_prompt.messages)
                if self.chat_prompt.messages
                else None
            )
            base_copy.chat_prompt = ChatPrompt(
                system=self.chat_prompt.system,
                prompt=self.chat_prompt.prompt,
                messages=messages,
            )

        # Deep copy tools dictionary and its contents if it exists
        if self.tools:
            base_copy.tools = copy.deepcopy(self.tools)

        return base_copy


class OptimizableAgent:
    """
    An agent class to subclass to make an Optimizable Agent.

    Attributes:
        model (Optional[str]): The model to use for the agent
        model_kwargs (Dict[str, Any]): Additional keyword arguments for the model
        project_name (Optional[str]): The project name for tracking
        input_dataset_field (Optional[str]): The field in the dataset to use as input
    """

    model: Optional[str] = None
    model_kwargs: Dict[str, Any] = {}
    project_name: Optional[str] = None
    input_dataset_field: Optional[str] = None
    agent_config: AgentConfig

    def __init__(self, agent_config: AgentConfig) -> None:
        """
        Initialize the OptimizableAgent.

        Args:
            agent_config (AgentConfig): Configuration for the agent
        """
        if self.project_name is None:
            self.project_name = "Default Project"
        self.init_llm()
        self.init_agent(agent_config)

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        # Litellm bug requires this (maybe problematic if multi-threaded)
        os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def init_agent(self, agent_config: AgentConfig) -> None:
        """Initialize the agent with the provided configuration."""
        self.agent_config = agent_config

    @_throttle.rate_limited(_limiter)
    def llm_invoke(
        self,
        query: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        seed: Optional[int] = None,
    ) -> str:
        """
        Invoke the LLM with the provided query or messages.

        Args:
            query (Optional[str]): The query to send to the LLM
            messages (Optional[List[Dict[str, str]]]): Messages to send to the LLM
            seed (Optional[int]): Seed for reproducibility

        Returns:
            str: The LLM's response
        """
        all_messages = []
        if query is not None:
            all_messages.append({"role": "user", "content": query})

        if messages is not None:
            all_messages.extend(messages)

        response = litellm.completion(
            model=self.model, messages=all_messages, seed=seed, **self.model_kwargs
        )
        result = response.choices[0].message.content
        return result

    def invoke(self, query: str) -> str:
        """
        Invoke the agent with a query.

        Args:
            query (str): The query to send to the agent

        Returns:
            str: The agent's response
        """
        return self.invoke_dataset_item({"question": query})

    def invoke_dataset_item(
        self,
        dataset_item: Dict[str, str],
        seed: Optional[int] = None,
    ) -> str:
        """
        Invoke the agent with a dataset item.

        Args:
            dataset_item (Dict[str, Any]): The dataset item to process
            seed (Optional[int]): Seed for reproducibility

        Returns:
            Dict[str, Any]: The agent's response
        """
        messages = []
        if self.agent_config.chat_prompt and self.agent_config.chat_prompt.system:
            messages.append(
                {"role": "system", "content": self.agent_config.chat_prompt.system}
            )
        if self.agent_config.chat_prompt and self.agent_config.chat_prompt.messages:
            messages.extend(self.agent_config.chat_prompt.messages)

        if self.input_dataset_field in dataset_item:
            messages.append(
                {"role": "user", "content": str(dataset_item[self.input_dataset_field])}
            )
        else:
            raise ValueError(
                f"Input field '{self.input_dataset_field}' not found in dataset item"
            )

        # Replace with agent invocation:
        result = self.llm_invoke(messages=messages, seed=seed)
        return result
