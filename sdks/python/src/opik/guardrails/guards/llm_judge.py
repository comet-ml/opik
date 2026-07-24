import json
import re
import traceback
from typing import Any, Dict, List, Optional

import pydantic

import opik.exceptions as exceptions
from opik import datetime_helpers, opik_context
from opik.api_objects import opik_client

from . import guard
from .. import schemas


_SYSTEM_PROMPT = """You are a guardrail that decides whether a piece of text complies with a policy.

Policy:
{instructions}

A text passes when it complies with the policy and fails when it violates it.
Respond with a single JSON object and nothing else, using this exact schema:
{{"passed": <true|false>, "reason": "<short explanation>"}}"""

_JSON_OBJECT_PATTERN = re.compile(r"\{.*\}", re.DOTALL)


class _LLMJudgeDecision(pydantic.BaseModel):
    passed: bool
    reason: str


def _usage_dict(response: Any) -> Optional[Dict[str, Any]]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return None

    values = {
        "prompt_tokens": usage.prompt_tokens,
        "completion_tokens": usage.completion_tokens,
        "total_tokens": usage.total_tokens,
    }
    if any(value is None for value in values.values()):
        return None

    return values


class LLMJudge(guard.Guard):
    """
    Guard that validates text against a natural-language policy using an LLM as a judge.

    The judge runs in the SDK and calls the Opik chat completions endpoint, which uses
    the LLM provider configured in your Opik workspace. It does not require the guardrails
    backend. The judge call is logged as a nested LLM span under the guardrail span.
    """

    local = True

    def __init__(
        self,
        name: str,
        instructions: str,
        model: str,
    ) -> None:
        """
        Initialize an LLM judge guard.

        Args:
            name: Name of the check, used to label the guardrail results.
            instructions: Natural-language policy describing what the text must comply with.
            model: Name of the model to judge with. Must be available through the LLM
                provider configured in your Opik workspace.
        """
        self._name = name
        self._instructions = instructions
        self._model = model

    def validate_local(
        self, text: str, client: opik_client.Opik
    ) -> List[schemas.ValidationResult]:
        messages = [
            {
                "role": "system",
                "content": _SYSTEM_PROMPT.format(instructions=self._instructions),
            },
            {"role": "user", "content": text},
        ]

        start_time = datetime_helpers.local_timestamp()

        # Any failure to run or parse the judgement fails closed (raises), so the
        # protected code path does not proceed on an inconclusive check.
        try:
            raw_response = client.rest_client.chat_completions.with_raw_response.create_chat_completions(
                model=self._model,
                temperature=0.0,
                messages=messages,  # type: ignore[arg-type]
            )
            response = raw_response.data
            # The provider and resolved model are returned as response headers, not
            # in the body, so they must be read from the raw response.
            provider = raw_response.headers.get("x-opik-provider")
            actual_model = raw_response.headers.get("x-opik-actual-model")
            content = response.choices[0].message.content
            decision = self._parse_decision(content)
        except Exception as e:
            self._log_span(
                client,
                messages,
                start_time,
                model=self._model,
                error_info={
                    "exception_type": type(e).__name__,
                    "message": str(e),
                    "traceback": traceback.format_exc(),
                },
            )
            raise exceptions.GuardrailValidationError(
                f"LLM judge '{self._name}' could not be evaluated, failing closed: {e}"
            ) from e

        self._log_span(
            client,
            messages,
            start_time,
            output={"content": content},
            usage=_usage_dict(response),
            model=actual_model or getattr(response, "model", None) or self._model,
            provider=provider,
        )

        return [
            schemas.ValidationResult(
                validation_passed=decision.passed,
                type=schemas.ValidationType.LLM_JUDGE,
                validation_config={
                    "name": self._name,
                    "instructions": self._instructions,
                    "model": self._model,
                },
                validation_details={
                    "name": self._name,
                    "passed": decision.passed,
                    "reason": decision.reason,
                },
            )
        ]

    def _log_span(
        self,
        client: opik_client.Opik,
        messages: List[Dict[str, str]],
        start_time: Any,
        **kwargs: Any,
    ) -> None:
        # Log the judge call as a nested LLM span under the guardrail span, in a
        # single create call (with start and end times) to avoid the data loss
        # that a create-then-end update can hit under batching. Recording is
        # best-effort and must never affect the guardrail outcome.
        current_span = opik_context.get_current_span_data()
        if current_span is None:
            return

        try:
            client.span(
                trace_id=current_span.trace_id,
                parent_span_id=current_span.id,
                name="llm_judge",
                type="llm",
                input={"messages": messages},
                start_time=start_time,
                end_time=datetime_helpers.local_timestamp(),
                **kwargs,
            )
        except Exception:
            pass

    def _parse_decision(self, content: str) -> _LLMJudgeDecision:
        match = _JSON_OBJECT_PATTERN.search(content or "")
        if match is None:
            raise ValueError(f"LLM judge returned a non-JSON response: {content}")

        return _LLMJudgeDecision.model_validate(json.loads(match.group(0)))
