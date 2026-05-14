"""AgenticLLMJudge — sibling of LLMJudge that drives the tool-call loop.

Kept as a separate class (rather than a branch inside LLMJudge) so the
agentic complexity stays out of the existing one-shot code path and the
diff against the v1 stays small. `LLMJudge.score` instantiates one of
these lazily when a `TraceToolContext` is available (see
`metric.py:score`).

Sync-only for Phase 1. Async will follow in a later phase if needed —
the tool-call loop's data shape is the same.
"""

import logging
from typing import Any, List, Optional

import tenacity

from opik import exceptions
from opik.evaluation.metrics import score_result
from opik.evaluation.models import base_model
from opik.evaluation.suite_evaluators.llm_judge import parsers

from . import context, loop, prompt
from .tools import get_trace_spans, read, registry as tool_registry

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
        user_prompt = prompt.AGENTIC_JUDGE_USER_TEMPLATE.format(
            assertions=schema.format_assertions(),
            input=_format_value(ctx.trace.input),
            output=_format_value(ctx.trace.output),
        )
        content = loop.run_agentic_judge(
            model=self._model,
            system_prompt=prompt.AGENTIC_JUDGE_SYSTEM_PROMPT,
            user_prompt=user_prompt,
            wrapup_instruction=prompt.WRAPUP_INSTRUCTION,
            registry=self._registry,
            ctx=ctx,
            response_format=schema.response_format,
        )
        return schema.parse(content)


def _default_registry() -> tool_registry.ToolRegistry:
    return tool_registry.ToolRegistry(
        tools=[
            get_trace_spans.GetTraceSpansTool(),
            read.ReadTool(),
        ]
    )
