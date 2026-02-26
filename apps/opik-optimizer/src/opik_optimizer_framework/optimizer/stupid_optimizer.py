from __future__ import annotations

import json
import logging
from typing import Any

import litellm

from opik_optimizer_framework.evaluation_adapter import EvaluationAdapter
from opik_optimizer_framework.event_emitter import EventEmitter
from opik_optimizer_framework.optimizer.prompts import IMPROVE_PROMPT_TEMPLATE
from opik_optimizer_framework.types import (
    CandidateConfig,
    OptimizationContext,
    OptimizationResult,
    OptimizationState,
    TrialResult,
)

logger = logging.getLogger(__name__)


class StupidOptimizer:
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
    ) -> OptimizationResult:
        state.total_steps = 2

        # Step 1: Generate 3 candidates from original prompt
        event_emitter.on_step_started(0, 2)
        state.step_index = 0
        step1_trials = self._run_step(
            context=context,
            base_messages=context.prompt_messages,
            count=3,
            step_index=0,
            parent_ids=[],
            training_set=training_set,
            validation_set=validation_set,
            evaluation_adapter=evaluation_adapter,
            state=state,
            event_emitter=event_emitter,
        )

        # Step 2: Generate 2 candidates from the best of step 1
        event_emitter.on_step_started(1, 2)
        state.step_index = 1
        best_step1 = state.best_trial
        if best_step1 is not None:
            base_messages = best_step1.prompt_messages
            parent_ids = [best_step1.candidate_id]
        else:
            base_messages = context.prompt_messages
            parent_ids = []

        step2_trials = self._run_step(
            context=context,
            base_messages=base_messages,
            count=2,
            step_index=1,
            parent_ids=parent_ids,
            training_set=training_set,
            validation_set=validation_set,
            evaluation_adapter=evaluation_adapter,
            state=state,
            event_emitter=event_emitter,
        )

        all_trials = step1_trials + step2_trials
        best = state.best_trial
        score = best.score if best else 0.0

        return OptimizationResult(
            best_trial=best,
            all_trials=all_trials,
            score=score,
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
    ) -> list[TrialResult]:
        trials: list[TrialResult] = []
        max_attempts = count * 3

        for attempt in range(max_attempts):
            if len(trials) >= count:
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
                trials.append(trial)
                event_emitter.on_progress(step_index, len(trials), count)

        return trials

    def _generate_candidate(
        self,
        base_messages: list[dict[str, str]],
        model: str,
        model_parameters: dict[str, Any],
    ) -> list[dict[str, str]] | None:
        """Use LLM to generate an improved prompt variant."""
        prompt_text = json.dumps(base_messages, indent=2)
        improve_prompt = IMPROVE_PROMPT_TEMPLATE.format(
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
