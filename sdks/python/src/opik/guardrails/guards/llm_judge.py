import json
import re
from typing import List, Optional, Union

import pydantic

import opik.exceptions as exceptions
from opik.api_objects import opik_client
from opik.evaluation.models import base_model, models_factory

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


class LLMJudge(guard.Guard):
    """
    Guard that validates text against a natural-language policy using an LLM as a judge.

    The judge runs in the SDK, calling the model directly with the credentials configured
    where your application runs (the usual provider environment variables), so any model
    LiteLLM supports can be used. It does not require the guardrails backend, nor an LLM
    provider configured in your Opik workspace. The judge call is logged as a nested LLM
    span under the guardrail span.
    """

    local = True

    def __init__(
        self,
        name: str,
        instructions: str,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
    ) -> None:
        """
        Initialize an LLM judge guard.

        Args:
            name: Name of the check, used to label the guardrail results.
            instructions: Natural-language policy describing what the text must comply with.
            model: The LLM to judge with. Can be a string (model name) or an
                `opik.evaluation.models.OpikBaseModel` subclass instance. The model runs
                where your application runs, so its provider credentials have to be
                available there. Defaults to the model configured as ``default_llm``.
        """
        self._name = name
        self._instructions = instructions
        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(
                model_name=model, track=True, temperature=0.0
            )

    def validate_local(
        self, text: str, client: opik_client.Opik
    ) -> List[schemas.ValidationResult]:
        messages: List[base_model.ConversationDict] = [
            {
                "role": "system",
                "content": _SYSTEM_PROMPT.format(instructions=self._instructions),
            },
            {"role": "user", "content": text},
        ]

        # Any failure to run or parse the judgement fails closed (raises), so the
        # protected code path does not proceed on an inconclusive check. The call itself
        # is logged as a nested LLM span by the tracked model.
        try:
            message = self._model.generate_chat_completion(
                messages=messages, response_format=_LLMJudgeDecision
            )
            content = message["content"]
            decision = self._parse_decision(content)
        except Exception as e:
            raise exceptions.GuardrailValidationError(
                f"LLM judge '{self._name}' could not be evaluated, failing closed: {e}"
            ) from e

        return [
            schemas.ValidationResult(
                validation_passed=decision.passed,
                type=schemas.ValidationType.LLM_JUDGE,
                validation_config={
                    "name": self._name,
                    "instructions": self._instructions,
                    "model": self._model.model_name,
                },
                validation_details={
                    "name": self._name,
                    "passed": decision.passed,
                    "reason": decision.reason,
                },
            )
        ]

    def _parse_decision(self, content: str) -> _LLMJudgeDecision:
        match = _JSON_OBJECT_PATTERN.search(content or "")
        if match is None:
            raise ValueError(f"LLM judge returned a non-JSON response: {content}")

        return _LLMJudgeDecision.model_validate(json.loads(match.group(0)))
