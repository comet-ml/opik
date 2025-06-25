from typing import Dict, Any, List, Optional, TYPE_CHECKING
import json
import os


from opik.opik_context import get_current_span_data

import litellm
from litellm.integrations.opik.opik import OpikLogger

from . import _throttle

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

if TYPE_CHECKING:
    from .optimization_config.chat_prompt import ChatPrompt


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


class OptimizableAgent:
    """
    An agent class to subclass to make an Optimizable Agent.

    Attributes:
        model (Optional[str]): The model to use for the agent
        model_kwargs (Dict[str, Any]): Additional keyword arguments for the model
        project_name (Optional[str]): The project name for tracking
    """

    model: Optional[str] = None
    model_kwargs: Dict[str, Any] = {}
    project_name: Optional[str] = "Default Project"
    input_dataset_field: Optional[str] = None
    prompts: Dict[str, "ChatPrompt"]
    prompt: "ChatPrompt"

    def __init__(self, prompt: "ChatPrompt") -> None:
        """
        Initialize the OptimizableAgent.

        Args:
            prompt: a chat prompt
        """
        self.init_llm()
        self.init_agent(prompt)

    def init_llm(self) -> None:
        """Initialize the LLM with the appropriate callbacks."""
        # Litellm bug requires this (maybe problematic if multi-threaded)
        os.environ["OPIK_PROJECT_NAME"] = str(self.project_name)
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def init_agent(self, prompt: "ChatPrompt") -> None:
        """Initialize the agent with the provided configuration."""
        # Register the tools, if any, for default LiteLLM Agent use:
        self.prompt = prompt

    @_throttle.rate_limited(_limiter)
    def _llm_complete(
        self,
        messages: List[Dict[str, str]],
        tools: Optional[List[Dict[str, str]]],
        seed: int,
    ) -> Any:
        response = litellm.completion(
            model=self.model,
            messages=messages,
            seed=seed,
            tools=tools,
            metadata={
                "opik": {
                    "current_span_data": get_current_span_data(),
                },
            },
            **self.model_kwargs,
        )
        return response

    def llm_invoke(
        self,
        query: Optional[str] = None,
        messages: Optional[List[Dict[str, str]]] = None,
        seed: Optional[int] = None,
        allow_tool_use: Optional[bool] = False,
    ) -> str:
        """
        NOTE: this is the default LiteLLM API. It is used
        internally for the LiteLLM Agent.

        Invoke the LLM with the provided query or messages.

        Args:
            query (Optional[str]): The query to send to the LLM
            messages (Optional[List[Dict[str, str]]]): Messages to send to the LLM
            seed (Optional[int]): Seed for reproducibility
            allow_tool_use: If True, allow LLM to use tools

        Returns:
            str: The LLM's response
        """
        all_messages = []
        if messages is not None:
            all_messages.extend(messages)

        if query is not None:
            all_messages.append({"role": "user", "content": query})

        if allow_tool_use and self.prompt.tools:
            # Tool-calling loop
            final_response = "I was unable to find the desired information."
            count = 0
            while count < 20:
                count += 1
                response = self._llm_complete(all_messages, self.prompt.tools, seed)
                msg = response.choices[0].message
                all_messages.append(msg.to_dict())
                if msg.tool_calls:
                    for tool_call in msg["tool_calls"]:
                        tool_name = tool_call["function"]["name"]
                        arguments = json.loads(tool_call["function"]["arguments"])
                        tool_func = self.prompt.function_map.get(tool_name)
                        try:
                            tool_result = (
                                tool_func(**arguments)
                                if tool_func is not None
                                else "Unknown tool"
                            )
                        except Exception:
                            tool_result = f"Error in calling tool `{tool_name}`"
                        all_messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call["id"],
                                "content": str(tool_result),
                            }
                        )
                else:
                    final_response = msg["content"]
                    break
            result = final_response
        else:
            response = self._llm_complete(all_messages, None, seed)
            result = response.choices[0].message.content
        return result

    def invoke_dataset_item(self, dataset_item: Dict[str, str]) -> str:
        messages = self.prompt.get_messages(dataset_item)
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
        result = self.llm_invoke(messages=messages, seed=seed, allow_tool_use=True)
        return result
