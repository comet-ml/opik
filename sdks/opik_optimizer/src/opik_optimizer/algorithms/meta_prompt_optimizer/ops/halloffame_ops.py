"""
Hall of Fame for tracking winning prompts and extracting successful patterns.

This module implements meta-learning: discovering what makes prompts successful
and re-injecting those patterns into future candidate generation.
"""

# TODO: Move into a shared extension module when Hall-of-Fame is reused by other optimizers.

from typing import Any
import json
from collections import Counter
import re
import logging

from .. import prompts as meta_prompts
from ..types import HallOfFameEntry
from ....utils.prompt_library import PromptLibrary
from ....utils.display import format as display_format
from ....utils.logging import debug_log

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
            debug_log(
                "hall_of_fame_add",
                score=entry.score,
                trial=entry.trial_number,
                action="append",
            )
            return True
        elif entry.score > self.entries[-1].score:
            removed = self.entries[-1]
            self.entries[-1] = entry
            self.entries.sort(key=lambda e: e.score, reverse=True)
            debug_log(
                "hall_of_fame_add",
                score=entry.score,
                trial=entry.trial_number,
                action="replace",
                replaced_score=removed.score,
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


def maybe_extract_hof_patterns(
    *,
    optimizer: Any,
    current_trial: int,
    metric_name: str,
) -> list[str]:
    """
    Extract Hall-of-Fame patterns when the extraction interval is reached.
    """
    if not optimizer.hall_of_fame:
        return []

    if not optimizer.hall_of_fame.should_extract_patterns(current_trial):
        return []

    logger.info(
        "Extracting patterns from hall of fame at trial %s",
        current_trial,
    )
    new_patterns = optimizer.hall_of_fame.extract_patterns(
        model=optimizer.model,
        model_parameters=optimizer.model_parameters,
        metric_name=metric_name,
    )
    if new_patterns:
        logger.info("Extracted %s new patterns", len(new_patterns))
        for i, pattern in enumerate(new_patterns[:3], 1):
            logger.debug("  Pattern %s: %s...", i, pattern[:100])
    return new_patterns


def get_patterns_for_injection(optimizer: Any) -> list[str] | None:
    """
    Return the current Hall-of-Fame patterns for injection, if enabled.
    """
    if optimizer.hall_of_fame:
        return optimizer.hall_of_fame.get_patterns_for_injection()
    return None


def build_hall_of_fame_context(
    *,
    hall_of_fame: Any,
    pretty_mode: bool,
    max_entries: int,
) -> str:
    """
    Build history context block from Hall of Fame entries.
    """
    if not hasattr(hall_of_fame, "entries") or not hall_of_fame.entries:
        return ""

    context = "\nHall of Fame: Best Performing Prompts Across All Rounds:\n"
    context += "=" * 80 + "\n"
    context += (
        "Study these top performers carefully - what patterns make them successful?\n\n"
    )

    for i, entry in enumerate(hall_of_fame.entries[:max_entries], 1):
        improvement_pct = entry.improvement_over_baseline * 100
        context += (
            f"\n#{i} WINNER | Trial {entry.trial_number} | "
            f"Score: {entry.score:.4f} | Improvement: {improvement_pct:+.1f}%\n"
        )

        if pretty_mode:
            context += "Full Prompt Messages:\n"
            context += display_format.format_prompt_messages(
                entry.prompt_messages, pretty=True
            )
            context += "\n\n"
        else:
            context += "Prompt:\n"
            context += display_format.format_prompt_messages(
                entry.prompt_messages, pretty=False
            )
            context += "\n"

        if entry.extracted_patterns:
            context += f"Why it worked: {', '.join(entry.extracted_patterns)}\n"

    context += "\n" + "=" * 80 + "\n"
    return context


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
    optimizer.hall_of_fame.add(entry)
