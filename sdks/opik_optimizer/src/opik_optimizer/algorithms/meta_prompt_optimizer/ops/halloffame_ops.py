"""
Hall of Fame for tracking winning prompts and extracting successful patterns.

This module implements meta-learning: discovering what makes prompts successful
and re-injecting those patterns into future candidate generation.
"""

from typing import Any
import json
from collections import Counter
import re
import logging

from .. import prompts as meta_prompts
from ..types import HallOfFameEntry
from ....utils.prompt_library import PromptLibrary

logger = logging.getLogger(__name__)


class PromptHallOfFame:
    """
    Maintains top-K prompts and extracts winning patterns for re-injection.

    This implements the core meta-learning loop:
    1. Track high-scoring prompts
    2. Extract patterns that correlate with success
    3. Inject patterns into new candidate generation
    4. Learn what makes prompts effective over time
    """

    def __init__(
        self,
        max_size: int = 10,
        pattern_extraction_interval: int = 5,
        prompts: PromptLibrary | None = None,
    ):
        """
        Initialize the Hall of Fame.

        Args:
            max_size: Maximum number of prompts to keep
            pattern_extraction_interval: Extract patterns every N trials
            prompts: PromptLibrary instance for accessing prompt templates
        """
        self.max_size = max_size
        self.pattern_extraction_interval = pattern_extraction_interval
        self.entries: list[HallOfFameEntry] = []
        self.extracted_patterns: list[str] = []
        self.pattern_usage_count: Counter = Counter()
        self._last_extraction_trial: int = 0
        self.prompts = prompts

    def add(self, entry: HallOfFameEntry) -> bool:
        """
        Add entry if it qualifies for hall of fame.

        Args:
            entry: HallOfFameEntry to potentially add

        Returns:
            True if entry was added, False otherwise
        """
        # Check if score is high enough
        if len(self.entries) < self.max_size:
            self.entries.append(entry)
            self.entries.sort(key=lambda e: e.score, reverse=True)
            logger.debug(
                f"Added to hall of fame: score={entry.score:.3f}, trial={entry.trial_number}"
            )
            return True
        elif entry.score > self.entries[-1].score:
            removed = self.entries[-1]
            self.entries[-1] = entry
            self.entries.sort(key=lambda e: e.score, reverse=True)
            logger.debug(
                f"Added to hall of fame (replaced score={removed.score:.3f}): "
                f"score={entry.score:.3f}, trial={entry.trial_number}"
            )
            return True
        return False

    def should_extract_patterns(self, current_trial: int) -> bool:
        """
        Check if we should run pattern extraction at this trial.
        """
        trials_since_last = current_trial - self._last_extraction_trial
        return (
            len(self.entries) >= 3
            and trials_since_last >= self.pattern_extraction_interval
        )

    def extract_patterns(
        self, model: str, model_parameters: dict[str, Any], metric_name: str
    ) -> list[str]:
        """
        Extract winning patterns from hall of fame using LLM.
        """
        if len(self.entries) < 3:
            logger.warning("Not enough entries for pattern extraction")
            return []

        top_prompts = self.entries[: min(5, len(self.entries))]
        prompt_analysis = self._build_pattern_extraction_prompt(
            top_prompts, metric_name
        )

        from ....core import llm_calls as _llm_calls

        if self.prompts is not None:
            system_prompt = self.prompts.get("pattern_extraction_system")
        else:
            system_prompt = meta_prompts.PATTERN_EXTRACTION_SYSTEM_PROMPT_TEMPLATE

        try:
            response = _llm_calls.call_model(
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt_analysis},
                ],
                model=model,
                model_parameters=model_parameters,
                is_reasoning=True,
                return_all=_llm_calls.requested_multiple_candidates(model_parameters),
            )

            responses = response if isinstance(response, list) else [response]
            patterns: list[str] = []
            for response_item in responses:
                patterns.extend(self._parse_pattern_response(response_item))

            if patterns:
                logger.debug("Extracted %s patterns from hall of fame", len(patterns))
                for i, pattern in enumerate(patterns, 1):
                    logger.debug("  Pattern %s: %s...", i, pattern[:100])

                self.extracted_patterns.extend(patterns)

                for entry in top_prompts:
                    entry.extracted_patterns = self._identify_patterns_in_prompt(
                        entry.prompt_messages, patterns
                    )

                self._last_extraction_trial = top_prompts[-1].trial_number

            return patterns

        except Exception as exc:
            logger.warning("Pattern extraction failed: %s", exc, exc_info=True)
            return []

    def get_patterns_for_injection(self, n: int = 3) -> list[str]:
        """
        Get top patterns to inject into new prompt generation.
        """
        if not self.extracted_patterns:
            return []

        pattern_scores: dict[str, float] = {}
        for pattern in set(self.extracted_patterns):
            using_entries = [
                e
                for e in self.entries
                if e.extracted_patterns and pattern in e.extracted_patterns
            ]

            if using_entries:
                avg_score = sum(e.score for e in using_entries) / len(using_entries)
                usage_penalty = 1.0 / (1.0 + self.pattern_usage_count[pattern] * 0.1)
                pattern_scores[pattern] = avg_score * usage_penalty

        sorted_patterns = sorted(
            pattern_scores.items(), key=lambda x: x[1], reverse=True
        )
        selected = [p for p, _ in sorted_patterns[:n]]

        for p in selected:
            self.pattern_usage_count[p] += 1

        logger.debug("Selected %s patterns for injection", len(selected))

        return selected

    def _build_pattern_extraction_prompt(
        self, top_entries: list[HallOfFameEntry], metric_name: str
    ) -> str:
        prompt_scorecard = ""
        for i, entry in enumerate(top_entries):
            prompt_scorecard += (
                f"\n--- Prompt #{i + 1} (Score: {entry.score:.3f}, "
                f"Improvement: {entry.improvement_over_baseline:+.1%}) ---\n"
            )
            prompt_scorecard += json.dumps(entry.prompt_messages, indent=2)
            prompt_scorecard += "\n"

        if self.prompts is not None:
            template = self.prompts.get("pattern_extraction_user")
        else:
            template = meta_prompts.PATTERN_EXTRACTION_USER_PROMPT_TEMPLATE

        return meta_prompts.build_pattern_extraction_user_prompt(
            template=template,
            top_prompts_scorecard=prompt_scorecard,
            metric_name=metric_name,
        )

    def _parse_pattern_response(self, response: str) -> list[str]:
        try:
            from ....utils.helpers import json_to_dict
            from ....utils.text import normalize_llm_text

            parsed = json_to_dict(normalize_llm_text(response))
            patterns: list[str] = []
            for item in parsed.get("patterns", []):
                if isinstance(item, dict):
                    pattern_desc = item.get("pattern", "")
                    example = item.get("example", "")
                    if pattern_desc:
                        if example:
                            pattern_desc = f"{pattern_desc} | Example: {example}"
                        patterns.append(pattern_desc)
                elif isinstance(item, str):
                    patterns.append(item)
            return patterns[:5]
        except Exception as exc:
            logger.debug("JSON parsing failed, using fallback: %s", exc)
            patterns = re.findall(r"(?:[-•\d]+\.?)\s*(.+?)(?:\n|$)", response)
            return [p.strip() for p in patterns[:5] if len(p.strip()) > 10]

    def _identify_patterns_in_prompt(
        self, prompt_messages: list[dict[str, str]], patterns: list[str]
    ) -> list[str]:
        prompt_text = " ".join([m.get("content", "") for m in prompt_messages]).lower()

        matched: list[str] = []
        for pattern in patterns:
            pattern_main = pattern.split("|")[0] if "|" in pattern else pattern
            pattern_keywords = pattern_main.lower().split()[:5]

            if any(kw in prompt_text for kw in pattern_keywords if len(kw) > 3):
                matched.append(pattern)

        return matched


def add_best_candidate_to_hof(
    *,
    optimizer: Any,
    best_candidate_this_round: Any,
    best_cand_score_avg: float,
    initial_score: float,
    current_trial: int,
    metric_name: str,
    is_bundle: bool,
) -> None:
    """Add the best candidate to the Hall of Fame if it qualifies."""
    if not optimizer.hall_of_fame or best_cand_score_avg <= 0 or is_bundle:
        return

    # For single prompt optimization, extract the ChatPrompt from dict
    if isinstance(best_candidate_this_round, dict):
        best_candidate_chat = list(best_candidate_this_round.values())[0]
    else:
        best_candidate_chat = best_candidate_this_round

    entry = HallOfFameEntry(
        prompt_messages=best_candidate_chat.get_messages(),
        score=best_cand_score_avg,
        trial_number=current_trial,
        improvement_over_baseline=(
            (best_cand_score_avg - initial_score) / initial_score
            if initial_score > 0
            else 0
        ),
        metric_name=metric_name,
    )
    if optimizer.hall_of_fame.add(entry):
        logger.debug(
            "Added to hall of fame: score=%.3f, trial=%s",
            best_cand_score_avg,
            current_trial,
        )
