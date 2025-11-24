from __future__ import annotations

from typing import Any
from collections.abc import Callable

from ...optimizable_agent import OptimizableAgent
from ...api_objects import chat_prompt


class BundleAgent(OptimizableAgent):
    """
    Orchestrates a mapping of named ChatPrompts as a single pipeline.

    - Executes prompts in a specified order/plan.
    - Uses each ChatPrompt's invoke/llm_invoke (tools/model kwargs preserved).
    - Returns final output and a simple trace.
    """

    def __init__(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        plan: list[str] | Callable[[dict[str, Any]], list[str]] | None = None,
        project_name: str | None = None,
    ) -> None:
        # Seed with a dummy prompt; we override prompt access per step.
        first_prompt = next(iter(prompts.values()))
        super().__init__(prompt=first_prompt, project_name=project_name)
        self.prompts = prompts
        self.plan = plan

    def run_bundle(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        # Resolve execution order
        if callable(self.plan):
            agent_order = self.plan(dataset_item)
        elif isinstance(self.plan, list):
            agent_order = self.plan
        else:
            agent_order = list(self.prompts.keys())

        trace: dict[str, Any] = {"steps": []}
        last_output: Any = None

        for name in agent_order:
            prompt = self.prompts[name]
            messages = prompt.get_messages(dataset_item)
            invoke_fn = getattr(prompt, "invoke", None)
            if callable(invoke_fn):
                output = invoke_fn(messages)
            else:
                # Fallback to no-op for tests without API keys
                output = ""
            trace["steps"].append(
                {
                    "agent": name,
                    "messages": messages,
                    "output": output,
                }
            )
            # Allow downstream agents to see prior output if dataset_item includes a mutable field
            dataset_item = dict(dataset_item)
            dataset_item[name + "_output"] = output
            last_output = output

        return {"final_output": last_output, "trace": trace}
