import threading
from typing import Optional, Tuple

import pydantic
import torch
import transformers

from opik_guardrails import schemas

from .. import _classifier
from .. import base_validator
from . import model_loader


class PromptInjectionValidationDetails(pydantic.BaseModel):
    injection_score: float


_Loaded = Tuple[torch.nn.Module, transformers.PreTrainedTokenizer, int, int, str]


class PromptInjectionValidator(base_validator.BaseValidator):
    """Detects prompt injection / jailbreak attempts with a fine-tuned classifier.

    The model (a Qwen LoRA adapter over a base decoder LM) is downloaded lazily on
    first use so the server can start without a Hugging Face token when this
    guardrail is not used.
    """

    def __init__(
        self,
        base_model: Optional[str],
        adapter_repo: Optional[str],
        token: Optional[str],
    ) -> None:
        self._base_model = base_model
        self._adapter_repo = adapter_repo
        self._token = token
        self._loaded: Optional[_Loaded] = None
        self._lock = threading.Lock()

    def _ensure_loaded(self) -> _Loaded:
        if self._loaded is None:
            with self._lock:
                if self._loaded is None:
                    self._loaded = model_loader.load_model(
                        self._base_model, self._adapter_repo, self._token
                    )
        return self._loaded

    def validate(
        self,
        text: str,
        config: schemas.PromptInjectionValidationConfig,
    ) -> schemas.ValidationResult:
        model, tokenizer, true_id, false_id, device = self._ensure_loaded()

        injection_score = _classifier.positive_class_score(
            model,
            tokenizer,
            true_id,
            false_id,
            device,
            text,
            model_loader.PROMPT_TEMPLATE,
        )

        passed = injection_score < config.threshold

        return schemas.ValidationResult(
            validation_passed=passed,
            validation_details=PromptInjectionValidationDetails(
                injection_score=injection_score
            ),
            type=schemas.ValidationType.PROMPT_INJECTION,
            validation_config=config,
        )
