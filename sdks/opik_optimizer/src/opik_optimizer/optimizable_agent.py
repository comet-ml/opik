from typing import Dict, Any, List, Optional, Callable
import random
import os
import copy

from pydantic import BaseModel, Field

from opik import Dataset

import litellm
from litellm.integrations.opik.opik import OpikLogger

from . import _throttle, task_evaluator
from .optimization_config.chat_prompt import ChatPrompt
from .optimization_config import mappers

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


def tools_to_dict(tools: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    retval = {}
    for name in tools:
        parts = {}
        for part in tools[name]:
            if isinstance(tools[name][part], (int, float, str)):
                parts[part] = tools[name][part]
        if parts:
            retval[name] = parts
    return retval


class AgentConfig(BaseModel):
    """Configuration model for the agent"""

    # Required:
    chat_prompt: ChatPrompt = Field(
        default=None, description="The chat prompt configuration for the agent"
    )
    tools: Optional[Dict[str, Any]] = Field(
        default=None, description="Optional dictionary of tools available to the agent"
    )

    class Config:
        arbitrary_types_allowed = True  # Allow ChatPrompt type

    def get_messages(
        self, dataset_item: Optional[Dict[str, str]] = None
    ) -> List[Dict[str, str]]:
        return self.chat_prompt.get_messages(dataset_item)

    def get_tools(self) -> Dict[str, Any]:
        if self.tools:
            return self.tools
        else:
            return {}

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
            base_copy.chat_prompt = self.chat_prompt.copy()

        # Deep copy tools dictionary and its contents if it exists
        if self.tools:
            base_copy.tools = copy.deepcopy(self.tools)

        return base_copy

    def to_dict(self) -> Dict[str, Any]:
        retval = {}
        if self.chat_prompt:
            retval["chat_prompt"] = self.chat_prompt.to_dict()
        if self.tools:
            retval["tools"] = tools_to_dict(self.tools)
        return retval


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
        if messages is not None:
            all_messages.extend(messages)

        if query is not None:
            all_messages.append({"role": "user", "content": query})

        response = litellm.completion(
            model=self.model, messages=all_messages, seed=seed, **self.model_kwargs
        )
        result = response.choices[0].message.content
        return result

    def invoke_dataset_item(self, dataset_item: Dict[str, str]) -> str:
        messages = self.agent_config.chat_prompt.get_messages(dataset_item)
        return self.invoke(messages)

    def invoke(
        self,
        messages: List[Dict[str, str]],
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
        # Replace with agent invocation:
        result = self.llm_invoke(messages=messages, seed=seed)
        return result

    def evaluate(
        self,
        dataset: Dataset,
        metric: Callable,
        n_threads: int,
        verbose: int = 0,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
    ) -> float:
        prompt = self.agent_config.chat_prompt

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            messages = self.agent_config.chat_prompt.get_messages(dataset_item)
            raw_model_output = self.invoke(messages)
            cleaned_model_output = raw_model_output.strip()
            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output,
            }
            return result

        experiment_config = experiment_config or {}
        experiment_config["project_name"] = self.__class__.__name__
        experiment_config = {
            **experiment_config,
            **{
                "agent_class": self.__class__.__name__,
                "agent_config": self.agent_config.to_dict(),
                "metric": metric.__name__,
                "dataset": dataset.name,
                "configuration": {"prompt": (prompt.get_messages() if prompt else [])},
            },
        }

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            dataset_item_ids = random.sample(all_ids, n_samples)

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=n_threads,
            project_name=self.project_name,
            experiment_config=experiment_config,
            optimization_id=None,
            verbose=verbose,
        )
        return score
