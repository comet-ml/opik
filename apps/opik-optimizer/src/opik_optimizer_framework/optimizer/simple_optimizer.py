from __future__ import annotations

import json
import logging
from typing import Any

import litellm

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationContext,
    OptimizationState,
)

logger = logging.getLogger(__name__)

_IMPROVE_PROMPT_TEMPLATE = """\
You are an expert prompt engineer. Your task is to improve an LLM prompt.

Here is the current prompt (as a list of messages):
{current_prompt}

The prompt is used with the model: {model}

Generate an improved version of the prompt that is more likely to produce high-quality outputs.
Focus on:
- Clarity and specificity of instructions
- Better structure and organization
- More effective use of the system message
- Appropriate level of detail

Return ONLY the improved prompt as a JSON array of message objects.
Each message must have "role" and "content" fields.
Do not include any explanation or commentary outside the JSON array.
"""


class SimpleOptimizer:
    """A simple 2-step optimizer for testing the framework pipeline.

    Step 1: Generate 3 candidate prompts from the original.
    Step 2: Generate 2 candidates from the best of step 1.
    """

    def run(
        self,
        context: OptimizationContext,
        training_set: list[str],
        validation_set: list[str],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        event_emitter: EventEmitter,
    ) -> None:
        num_steps = context.optimizer_parameters.get("num_steps", 2)
        candidates_per_step = context.optimizer_parameters.get(
            "candidates_per_step", [3, 2]
        )
        if len(candidates_per_step) < num_steps:
            candidates_per_step = candidates_per_step + [
                candidates_per_step[-1]
            ] * (num_steps - len(candidates_per_step))

        state.total_steps = num_steps

        for step_index in range(num_steps):
            event_emitter.on_step_started(step_index, num_steps)
            state.step_index = step_index

            if step_index == 0:
                base_messages = context.prompt_messages
                parent_ids: list[str] = []
            else:
                best = state.best_trial
                if best is not None:
                    base_messages = best.prompt_messages
                    parent_ids = [best.candidate_id]
                else:
                    base_messages = context.prompt_messages
                    parent_ids = []

            self._run_step(
                context=context,
                base_messages=base_messages,
                count=candidates_per_step[step_index],
                step_index=step_index,
                parent_ids=parent_ids,
                training_set=training_set,
                validation_set=validation_set,
                evaluation_adapter=evaluation_adapter,
                state=state,
                event_emitter=event_emitter,
            )

    def _run_step(
        self,
        context: OptimizationContext,
        base_messages: list[dict[str, str]],
        count: int,
        step_index: int,
        parent_ids: list[str],
        training_set: list[str],
        validation_set: list[str],
        evaluation_adapter: EvaluationAdapter,
        state: OptimizationState,
        event_emitter: EventEmitter,
    ) -> None:
        completed = 0
        max_attempts = count * 3

        for attempt in range(max_attempts):
            if completed >= count:
                break

            candidate_messages = self._generate_candidate(
                base_messages=base_messages,
                model=context.model,
                model_parameters=context.model_parameters,
            )
            if candidate_messages is None:
                continue

            config = CandidateConfig(
                prompt_messages=candidate_messages,
                model=context.model,
                model_parameters=context.model_parameters,
            )

            trial = evaluation_adapter.evaluate(
                config=config,
                dataset_item_ids=training_set,
                step_index=step_index,
                parent_candidate_ids=parent_ids,
            )

            if trial is not None:
                completed += 1
                event_emitter.on_progress(step_index, completed, count)

    def _generate_candidate(
        self,
        base_messages: list[dict[str, str]],
        model: str,
        model_parameters: dict[str, Any],
    ) -> list[dict[str, str]] | None:
        """Use LLM to generate an improved prompt variant."""
        prompt_text = json.dumps(base_messages, indent=2)
        improve_prompt = _IMPROVE_PROMPT_TEMPLATE.format(
            current_prompt=prompt_text,
            model=model,
        )

        try:
            response = litellm.completion(
                model=model,
                messages=[{"role": "user", "content": improve_prompt}],
                **model_parameters,
            )
            content = response.choices[0].message.content
            return self._parse_messages(content)
        except Exception:
            logger.exception("Failed to generate candidate")
            return None

    def _parse_messages(self, content: str) -> list[dict[str, str]] | None:
        """Parse LLM output into a list of message dicts."""
        content = content.strip()
        if content.startswith("```"):
            lines = content.split("\n")
            lines = lines[1:]
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            content = "\n".join(lines)

        try:
            messages = json.loads(content)
        except json.JSONDecodeError:
            logger.warning("Failed to parse LLM output as JSON")
            return None

        if not isinstance(messages, list):
            return None

        for msg in messages:
            if not isinstance(msg, dict):
                return None
            if "role" not in msg or "content" not in msg:
                return None

        return messages
