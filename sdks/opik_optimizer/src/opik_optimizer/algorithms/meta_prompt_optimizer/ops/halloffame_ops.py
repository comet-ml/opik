"""
Hall of Fame for tracking winning prompts and extracting successful patterns.

This module implements meta-learning: discovering what makes prompts successful
and re-injecting those patterns into future candidate generation.
"""

from dataclasses import dataclass, field
from typing import Any
import json
from collections import Counter
import re
import logging

from ..prompts import (
    build_pattern_extraction_system_prompt,
    build_pattern_extraction_user_prompt,
)

logger = logging.getLogger(__name__)


@dataclass
class HallOfFameEntry:
    """Represents a high-performing prompt in the hall of fame"""

    prompt_messages: list[dict[str, str]]
    score: float
    trial_number: int
    improvement_over_baseline: float
    metric_name: str
    extracted_patterns: list[str] | None = None  # Filled during pattern extraction
    metadata: dict[str, Any] = field(default_factory=dict)


class PromptHallOfFame:
    """
    Maintains top-K prompts and extracts winning patterns for re-injection.

    This implements the core meta-learning loop:
    1. Track high-scoring prompts
    2. Extract patterns that correlate with success
    3. Inject patterns into new candidate generation
    4. Learn what makes prompts effective over time
    """

    def __init__(self, max_size: int = 10, pattern_extraction_interval: int = 5):
        """
        Initialize the Hall of Fame.

        Args:
            max_size: Maximum number of prompts to keep
            pattern_extraction_interval: Extract patterns every N trials
        """
        self.max_size = max_size
        self.pattern_extraction_interval = pattern_extraction_interval
        self.entries: list[HallOfFameEntry] = []
        self.extracted_patterns: list[str] = []
        self.pattern_usage_count: Counter = Counter()
        self._last_extraction_trial: int = 0

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

        Args:
            current_trial: Current trial number

        Returns:
            True if pattern extraction should run
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
        This is the key innovation: meta-learning what makes prompts successful.

        Args:
            model: LLM model to use for pattern extraction
            model_parameters: Model parameters
            metric_name: Name of the metric being optimized

        Returns:
            List of extracted patterns
        """
        if len(self.entries) < 3:
            logger.warning("Not enough entries for pattern extraction")
            return []

        # Get top 5 prompts
        top_prompts = self.entries[: min(5, len(self.entries))]

        # Build analysis prompt
        prompt_analysis = self._build_pattern_extraction_prompt(
            top_prompts, metric_name
        )

        from .... import _llm_calls

        try:
            response = _llm_calls.call_model(
                messages=[
                    {
                        "role": "system",
                        "content": build_pattern_extraction_system_prompt(),
                    },
                    {"role": "user", "content": prompt_analysis},
                ],
                model=model,
                model_parameters=model_parameters,
                is_reasoning=True,
            )

            # Parse extracted patterns
            patterns = self._parse_pattern_response(response)

            if patterns:
                logger.debug(f"Extracted {len(patterns)} patterns from hall of fame")
                for i, pattern in enumerate(patterns, 1):
                    logger.debug(f"  Pattern {i}: {pattern[:100]}...")

                # Store patterns
                self.extracted_patterns.extend(patterns)

                # Update entries with their patterns
                for entry in top_prompts:
                    entry.extracted_patterns = self._identify_patterns_in_prompt(
                        entry.prompt_messages, patterns
                    )

                self._last_extraction_trial = top_prompts[-1].trial_number

            return patterns

        except Exception as e:
            logger.warning(f"Pattern extraction failed: {e}", exc_info=True)
            return []

    def get_patterns_for_injection(self, n: int = 3) -> list[str]:
        """
        Get top patterns to inject into new prompt generation.
        Balances pattern effectiveness with usage diversity.

        Args:
            n: Number of patterns to return

        Returns:
            List of top N patterns for injection
        """
        if not self.extracted_patterns:
            return []

        # Score patterns by: (1) score of prompts using them, (2) inverse usage count
        pattern_scores = {}
        for pattern in set(self.extracted_patterns):
            # Find prompts using this pattern
            using_entries = [
                e
                for e in self.entries
                if e.extracted_patterns and pattern in e.extracted_patterns
            ]

            if using_entries:
                avg_score = sum(e.score for e in using_entries) / len(using_entries)
                # Penalize frequently used patterns to encourage diversity
                usage_penalty = 1.0 / (1.0 + self.pattern_usage_count[pattern] * 0.1)
                pattern_scores[pattern] = avg_score * usage_penalty

        # Return top N patterns
        sorted_patterns = sorted(
            pattern_scores.items(), key=lambda x: x[1], reverse=True
        )
        selected = [p for p, _ in sorted_patterns[:n]]

        # Update usage counts
        for p in selected:
            self.pattern_usage_count[p] += 1

        logger.debug(f"Selected {len(selected)} patterns for injection")

        return selected

    def _build_pattern_extraction_prompt(
        self, top_entries: list[HallOfFameEntry], metric_name: str
    ) -> str:
        """Build prompt for LLM to extract patterns"""
        prompt_scorecard = ""
        for i, entry in enumerate(top_entries):
            prompt_scorecard += f"\n--- Prompt #{i + 1} (Score: {entry.score:.3f}, Improvement: {entry.improvement_over_baseline:+.1%}) ---\n"
            prompt_scorecard += json.dumps(entry.prompt_messages, indent=2)
            prompt_scorecard += "\n"

        return build_pattern_extraction_user_prompt(
            top_prompts_scorecard=prompt_scorecard,
            metric_name=metric_name,
        )

    def _parse_pattern_response(self, response: str) -> list[str]:
        """Parse LLM response into pattern list"""
        try:
            from ....utils.core import json_to_dict

            parsed = json_to_dict(response)
            patterns = []
            for item in parsed.get("patterns", []):
                if isinstance(item, dict):
                    # Combine pattern description and example
                    pattern_desc = item.get("pattern", "")
                    example = item.get("example", "")
                    if pattern_desc:
                        if example:
                            pattern_desc = f"{pattern_desc} | Example: {example}"
                        patterns.append(pattern_desc)
                elif isinstance(item, str):
                    patterns.append(item)
            return patterns[:5]  # Limit to 5 patterns
        except Exception as e:
            logger.debug(f"JSON parsing failed, using fallback: {e}")
            # Fallback: extract bullet points or numbered items
            patterns = re.findall(r"(?:[-•\d]+\.?)\s*(.+?)(?:\n|$)", response)
            return [p.strip() for p in patterns[:5] if len(p.strip()) > 10]

    def _identify_patterns_in_prompt(
        self, prompt_messages: list[dict[str, str]], patterns: list[str]
    ) -> list[str]:
        """Check which patterns appear in a prompt"""
        prompt_text = " ".join([m.get("content", "") for m in prompt_messages]).lower()

        matched = []
        for pattern in patterns:
            # Extract key words from pattern (before the '|' separator if present)
            pattern_main = pattern.split("|")[0] if "|" in pattern else pattern
            pattern_keywords = pattern_main.lower().split()[:5]  # First 5 words

            # Check if any keywords appear in prompt
            if any(kw in prompt_text for kw in pattern_keywords if len(kw) > 3):
                matched.append(pattern)

        return matched
