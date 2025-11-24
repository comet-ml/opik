from __future__ import annotations

from typing import Any
from collections.abc import Mapping
from collections.abc import Callable
import uuid

from opik import opik_context

from opik_optimizer.optimizable_agent import OptimizableAgent
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.utils.llm_logger import LLMLogger


class SequencedOptimizableAgent(OptimizableAgent):
    """Runs an ordered list of ChatPrompts as a single agent with one trace."""

    def __init__(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        plan: list[str] | Callable[[dict[str, Any]], list[str]] | None = None,
        project_name: str | None = None,
        graph_definition: dict[str, Any] | None = None,
        step_handlers: Mapping[str, Callable[[dict[str, Any], str], dict[str, Any]]]
        | None = None,
        logger: LLMLogger | None = None,
    ) -> None:
        first_prompt = next(iter(prompts.values()))
        super().__init__(prompt=first_prompt, project_name=project_name)
        self.prompts = prompts
        self.plan = plan
        self.graph_definition = graph_definition or {
            "format": "mermaid",
            "data": "graph TD; Q1[create_query_1]-->S1[summarize_1]; S1-->Q2[create_query_2]; Q2-->S2[summarize_2]; S2-->FA[final_answer];",
        }
        self.step_handlers = dict(step_handlers) if step_handlers else {}
        self.llm_logger = logger

    def get_graph_definition(self) -> dict[str, Any]:
        """Return the graph definition for display or tracing."""
        return self.graph_definition

    def _resolve_plan(self, dataset_item: dict[str, Any]) -> list[str]:
        if callable(self.plan):
            return self.plan(dataset_item)
        if isinstance(self.plan, list):
            # If plan entries match prompt names, use that order; otherwise fall back.
            if all(name in self.prompts for name in self.plan):
                return self.plan
        # Default: prompt insertion order
        return list(self.prompts.keys())

    def run(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        dataset_item = dict(dataset_item)  # work on a copy so we can enrich fields
        task_id = uuid.uuid4().hex[:8]
        try:
            # Only start a trace if one is not already active (bundle runner may have started it).
            if opik_context.get_current_trace_id() is None:  # type: ignore[attr-defined]
                opik_context.start_trace(
                    tags=["sequence", task_id],
                    metadata={"_opik_graph_definition": self.graph_definition},
                )
            else:
                opik_context.update_current_trace(
                    metadata={"_opik_graph_definition": self.graph_definition}
                )
        except Exception:
            pass

        trace: dict[str, Any] = {
            "steps": [],
            "graph": self.graph_definition,
            "task_id": task_id,
        }
        final_output: Any = ""
        for step_name in self._resolve_plan(dataset_item):
            prompt = self.prompts[step_name]
            try:
                opik_context.update_current_trace(metadata={"step": step_name})
                opik_context.start_span(name=step_name)
            except Exception:
                pass
            if self.llm_logger:
                self.llm_logger.agent_invoke(
                    f"{step_name}: {dataset_item.get('question', '')}"
                )
            step_output = self.invoke_prompt(prompt, dataset_item)
            messages = prompt.get_messages(dataset_item)
            handler = self.step_handlers.get(step_name)
            if handler:
                dataset_item = handler(dataset_item, step_output)
            # Expose the step output under the step name for downstream prompts
            dataset_item[step_name] = step_output
            trace["steps"].append(
                {
                    "agent": step_name,
                    "messages": messages,
                    "output": step_output,
                }
            )
            dataset_item = dict(dataset_item)
            dataset_item[step_name + "_output"] = step_output
            final_output = step_output
            try:
                opik_context.end_span()
            except Exception:
                pass

        return {"final_output": final_output, "trace": trace}

    def run_bundle(self, dataset_item: dict[str, Any]) -> dict[str, Any]:
        """Alias for compatibility with bundle evaluation hooks."""
        return self.run(dataset_item)
