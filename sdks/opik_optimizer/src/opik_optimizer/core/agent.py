from __future__ import annotations

from typing import Any, TYPE_CHECKING

from ..agents.optimizable_agent import OptimizableAgent
from ..api_objects import chat_prompt
from ..utils.tool_helpers import serialize_tools
from .. import helpers

if TYPE_CHECKING:  # pragma: no cover
    from ..agents.litellm_agent import LiteLLMAgent
__all__ = [
    "setup_agent_class",
    "bind_optimizer",
    "instantiate_agent",
    "build_agent_config",
]


def setup_agent_class(
    optimizer: Any,
    prompt: chat_prompt.ChatPrompt,
    agent_class: Any = None,
) -> Any:
    if agent_class is None:
        from ..agents.litellm_agent import LiteLLMAgent

        return LiteLLMAgent
    if not issubclass(agent_class, OptimizableAgent):
        raise TypeError(
            f"agent_class must inherit from OptimizableAgent, got {agent_class.__name__}"
        )
    return agent_class


def bind_optimizer(optimizer: Any, agent: OptimizableAgent) -> OptimizableAgent:
    try:
        agent.optimizer = optimizer  # type: ignore[attr-defined]
    except Exception:  # pragma: no cover - custom agents may forbid new attrs
        return agent
    return agent


def instantiate_agent(
    optimizer: Any,
    *args: Any,
    agent_class: type[OptimizableAgent] | None = None,
    **kwargs: Any,
) -> OptimizableAgent:
    resolved_class = agent_class or getattr(optimizer, "agent_class", None)
    if resolved_class is None:
        raise ValueError("agent_class must be provided before instantiation")
    agent = resolved_class(*args, **kwargs)
    return bind_optimizer(optimizer, agent)


def build_agent_config(
    *,
    optimizer: Any,
    prompt: chat_prompt.ChatPrompt,
) -> dict[str, Any]:
    agent_config: dict[str, Any] = dict(prompt.to_dict())
    agent_config["project_name"] = getattr(prompt, "project_name", None)
    agent_config["model"] = getattr(prompt, "model", None) or optimizer.model
    agent_config["tools"] = serialize_tools(prompt)
    agent_config["optimizer"] = optimizer.__class__.__name__
    return helpers.drop_none(agent_config)
