"""AgenticLLMJudge — sibling of LLMJudge that drives the tool-call loop.

Kept as a separate class (rather than a branch inside LLMJudge), so the
agentic complexity stays out of the existing one-shot code path, and the
diff against the v1 stays small. `LLMJudge.score` instantiates one of
these lazily when a `TraceToolContext` is available (see
`metric.py:score`).

Sync-only for Phase 1. Async will follow in a later phase if needed —
the tool-call loop's data shape is the same.
"""

import json
import logging
from typing import Any, List, Optional

import tenacity

from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model
from opik.evaluation.suite_evaluators.llm_judge import (
    parsers,
    strategy_selector,
)

from . import context, loop, prompt
from .compression import span_tree_serializer
from .tools import read, registry as tool_registry, scan, search

LOGGER = logging.getLogger(__name__)


_RETRY_POLICY = tenacity.retry(
    retry=tenacity.retry_if_exception_type(exceptions.LLMJudgeParseError),
    stop=tenacity.stop_after_attempt(3),
    before_sleep=tenacity.before_sleep_log(LOGGER, logging.WARNING),
    reraise=True,
)


def _format_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    import json

    return json.dumps(value, indent=2, default=str)


class AgenticLLMJudge:
    """Agentic counterpart to LLMJudge.

    Wraps the same model and assertion set as LLMJudge but runs the
    tool-call loop instead of a single-shot completion. The verdict
    schema reuses `parsers.ResponseSchema` so parsing behavior matches
    LLMJudge byte-for-byte (same per-assertion fields, same retry
    semantics, same ScoreResult shape).
    """

    def __init__(
        self,
        assertions: List[str],
        model: base_model.OpikBaseModel,
        registry: Optional[tool_registry.ToolRegistry] = None,
    ) -> None:
        self._assertions = list(assertions)
        self._model = model
        self._registry = registry or _default_registry()

    def score(self, ctx: context.TraceToolContext) -> List[score_result.ScoreResult]:
        try:
            return self._generate_and_parse(ctx)
        except exceptions.LLMJudgeParseError as exc:
            LOGGER.warning(
                "AgenticLLMJudge scoring failed after retries: %s",
                exc,
                exc_info=True,
            )
            return exc.results

    @_RETRY_POLICY
    def _generate_and_parse(
        self, ctx: context.TraceToolContext
    ) -> List[score_result.ScoreResult]:
        schema = parsers.ResponseSchema(self._assertions)
        # Size the inline overview against the judge model's context
        # budget — use the largest per-field truncation that still fits,
        # so capable models receive a richer first-turn view and need
        # fewer drill-in `read` calls.
        budget = strategy_selector.compute_budget_tokens(self._model.model_name)
        chosen_limit, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=ctx.trace,
            spans=ctx.spans,
            parent_by_child=ctx.parent_by_child,
            budget_tokens=budget,
        )
        overview_truncated = chosen_limit != span_tree_serializer.NO_OVERVIEW_TRUNCATION
        user_prompt = prompt.AGENTIC_JUDGE_USER_TEMPLATE.format(
            assertions=schema.format_assertions(),
            input=_format_value(ctx.trace.input),
            output=_format_value(ctx.trace.output),
            overview=json.dumps(overview, indent=2, default=str),
        )
        content = loop.run_agentic_judge(
            model=self._model,
            system_prompt=prompt.AGENTIC_JUDGE_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            wrapup_instruction=prompt.WRAPUP_INSTRUCTION,
            registry=self._registry,
            ctx=ctx,
            response_format=schema.response_format,
            overview_truncated=overview_truncated,
        )
        return schema.parse(content)


def _default_registry() -> tool_registry.ToolRegistry:
    return tool_registry.ToolRegistry(
        tools=[
            read.ReadTool(),
            scan.ScanTool(),
            search.SearchTool(),
        ]
    )
