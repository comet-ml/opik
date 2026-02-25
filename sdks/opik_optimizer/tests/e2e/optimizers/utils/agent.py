"""
Test agent for multi-prompt optimization e2e tests.

This module provides a simple multi-prompt agent that can be used
to test multi-prompt optimization across all optimizers.
"""

from __future__ import annotations

from typing import Any

import litellm
from opik import opik_context
from opik.integrations.litellm import track_completion

from opik_optimizer import ChatPrompt, OptimizableAgent


class MultiPromptTestAgent(OptimizableAgent):
    """
    A simple multi-prompt agent for testing multi-prompt optimization.

    This agent orchestrates two prompts:
    - "analyze": Analyzes the input and extracts key information
    - "respond": Generates a response based on the analysis
    """

    def __init__(
        self,
        model: str = "openai/gpt-5-nano",
        model_parameters: dict[str, Any] | None = None,
    ) -> None:
        super().__init__()
        self.model = model
        self.model_parameters = model_parameters or {}

    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        """
        Execute the multi-prompt pipeline.

        Args:
            prompts: Dict with "analyze" and "respond" ChatPrompt objects
            dataset_item: Dataset item containing the input
            allow_tool_use: Whether to allow tool use (not used in this agent)
            seed: Random seed for reproducibility

        Returns:
            Final response string
        """
        _ = allow_tool_use

        tracked_completion = track_completion()(litellm.completion)

        # Step 1: Analyze the input
        analyze_messages = prompts["analyze"].get_messages(dataset_item)
        analyze_response = tracked_completion(
            model=self.model,
            messages=analyze_messages,
            seed=seed,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                },
            },
            **self.model_parameters,
        )
        analysis = analyze_response.choices[0].message.content

        # Step 2: Generate response based on analysis
        respond_context = {**dataset_item, "analysis": analysis}
        respond_messages = prompts["respond"].get_messages(respond_context)
        respond_response = tracked_completion(
            model=self.model,
            messages=respond_messages,
            seed=seed,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                },
            },
            **self.model_parameters,
        )

        return respond_response.choices[0].message.content
