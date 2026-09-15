import threading
from typing import Dict, Tuple

import pydantic

from opik_guardrails import schemas

from .. import _classifier
from .. import base_validator
from . import model_loader


class CustomClassifierValidationDetails(pydantic.BaseModel):
    model_name: str
    score: float


class CustomClassifierValidator(base_validator.BaseValidator):
    """Serves user-trained binary guardrail classifiers by name.

    All adapters that share a base model are hosted on a single resident copy of
    that base (see ``model_loader.SharedBase``), so one guardrails server can
    serve many custom guardrails without loading the base once per adapter. The
    active adapter is a shared, per-base setting, so selecting it and running
    inference are serialized under a lock.
    """

    def __init__(self, adapters_dir: str) -> None:
        self._adapters_dir = adapters_dir
        self._bases: Dict[str, model_loader.SharedBase] = {}
        self._adapters: Dict[str, Tuple[str, str]] = {}
        self._lock = threading.Lock()

    def _resolve(self, model_name: str) -> Tuple[model_loader.SharedBase, str]:
        registered = self._adapters.get(model_name)
        if registered is None:
            base_model, prompt_template, adapter_path = model_loader.read_bundle(
                self._adapters_dir, model_name
            )
            base = self._bases.get(base_model)
            if base is None:
                base = model_loader.SharedBase(base_model)
                self._bases[base_model] = base
            base.ensure_adapter(model_name, adapter_path)
            registered = (base_model, prompt_template)
            self._adapters[model_name] = registered

        base_model, prompt_template = registered
        return self._bases[base_model], prompt_template

    def validate(
        self,
        text: str,
        config: schemas.CustomClassifierValidationConfig,
    ) -> schemas.ValidationResult:
        with self._lock:
            base, prompt_template = self._resolve(config.model_name)
            model = base.activate(config.model_name)

            score = _classifier.positive_class_score(
                model,
                base.tokenizer,
                base.true_id,
                base.false_id,
                base.device,
                text,
                prompt_template,
            )

        passed = score < config.threshold

        return schemas.ValidationResult(
            validation_passed=passed,
            validation_details=CustomClassifierValidationDetails(
                model_name=config.model_name, score=score
            ),
            type=schemas.ValidationType.CUSTOM_CLASSIFIER,
            validation_config=config,
        )
