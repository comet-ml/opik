import json
import os
from typing import Optional, Set, Tuple

import torch
import transformers
from peft import PeftModel

from .. import _classifier


def read_bundle(adapters_dir: str, model_name: str) -> Tuple[str, str, str]:
    """Return ``(base_model, prompt_template, adapter_path)`` for a stored bundle.

    Each model lives at ``<adapters_dir>/<model_name>/`` and is self-describing:
    an ``adapter/`` directory with the LoRA weights + tokenizer, and a
    ``metric.json`` carrying the base model and the prompt template it was
    trained with.
    """
    model_dir = os.path.join(adapters_dir, model_name)
    metric_path = os.path.join(model_dir, "metric.json")
    adapter_path = os.path.join(model_dir, "adapter")

    if not os.path.isfile(metric_path) or not os.path.isdir(adapter_path):
        raise ValueError(
            f"Custom guardrail model {model_name!r} was not found in the adapters "
            f"directory ({adapters_dir}). Train it first and make sure the directory "
            f"is available to the guardrails server."
        )

    with open(metric_path) as f:
        metric = json.load(f)

    return metric["base_model"], metric["prompt_template"], adapter_path


class SharedBase:
    """One resident base model hosting many LoRA adapters.

    peft keeps a single copy of the base weights and applies the selected
    adapter's small delta per forward pass, so serving N custom guardrails over
    the same base costs one base plus N adapters instead of N full copies.
    Adapters are attached lazily and activated by name before each inference.
    """

    def __init__(self, base_model: str) -> None:
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            self.device = os.getenv("OPIK_GUARDRAILS_DEVICE", "cuda:0")
            self._torch_dtype = torch.float16
        else:
            self.device = "cpu"
            self._torch_dtype = torch.float32

        self._base_model = base_model

        self.tokenizer = transformers.AutoTokenizer.from_pretrained(base_model)
        self.tokenizer.padding_side = "left"
        if self.tokenizer.pad_token is None:
            self.tokenizer.pad_token = self.tokenizer.eos_token

        self.true_id = _classifier.resolve_class_token(self.tokenizer, "true")
        self.false_id = _classifier.resolve_class_token(self.tokenizer, "false")

        self._model: Optional[PeftModel] = None
        self._adapters: Set[str] = set()

    def ensure_adapter(self, name: str, adapter_path: str) -> None:
        if name in self._adapters:
            return

        if self._model is None:
            base = transformers.AutoModelForCausalLM.from_pretrained(
                self._base_model, torch_dtype=self._torch_dtype
            )
            base.config.pad_token_id = self.tokenizer.pad_token_id
            self._model = PeftModel.from_pretrained(
                base, adapter_path, adapter_name=name
            )
        else:
            self._model.load_adapter(adapter_path, adapter_name=name)

        self._model.to(self.device)
        self._model.eval()
        self._adapters.add(name)

    def activate(self, name: str) -> torch.nn.Module:
        assert self._model is not None
        self._model.set_adapter(name)
        return self._model
