# mypy: ignore-errors
"""Train a custom binary guardrail classifier in-process on the guardrails server.

Given labeled examples and a natural-language description of the metric, fine-tunes
a LoRA adapter that answers with a single `true`/`false` token (the same structure
as the prompt injection classifier) and saves a self-describing bundle
(``adapter/`` + ``metric.json``) that the server can then serve by name.
"""

import json
import os
from typing import Any, Dict, List, Optional

import numpy as np
import torch
import torch.nn.functional as F
from datasets import Dataset
from peft import LoraConfig, get_peft_model
from sklearn.metrics import precision_recall_fscore_support, roc_auc_score
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    Trainer,
    TrainerCallback,
    TrainingArguments,
    set_seed,
)

from . import status as status_writer


class _ProgressCallback(TrainerCallback):
    """Streams training progress into status.json as the run proceeds."""

    def __init__(self, status_path: str) -> None:
        self._status_path = status_path
        self._train_loss: Optional[float] = None
        self._latest_eval: Dict[str, float] = {}

    def _write(self, state) -> None:
        percent = (
            int(100 * state.global_step / state.max_steps) if state.max_steps else 0
        )
        progress = {
            "epoch": round(state.epoch, 3) if state.epoch is not None else None,
            "step": state.global_step,
            "total_steps": state.max_steps,
            "percent": percent,
            "train_loss": self._train_loss,
            "latest_eval": self._latest_eval,
        }
        status_writer.write(self._status_path, "training", progress=progress)

    def on_log(self, args, state, control, logs=None, **kwargs):
        if logs and "loss" in logs:
            self._train_loss = logs["loss"]
        self._write(state)

    def on_evaluate(self, args, state, control, metrics=None, **kwargs):
        if metrics:
            self._latest_eval = {
                k: v for k, v in metrics.items() if k.startswith("eval_")
            }
        self._write(state)


def build_prompt_template(description: str) -> str:
    """Build the classifier prompt from a metric description.

    ``description`` completes the sentence "Determine whether it ..."; ``{text}``
    stays a literal placeholder that inference fills in, so this exact template is
    stored with the adapter and reused at serving time.
    """
    return (
        "You will be given a string. Determine whether it "
        + description
        + ".\n\nString:\n{text}\n\nAnswer:"
    )


def _resolve_class_token(tokenizer, word: str) -> int:
    for candidate in (f" {word}", word):
        ids = tokenizer.encode(candidate, add_special_tokens=False)
        if len(ids) == 1:
            return ids[0]
    raise ValueError(
        f"Class word {word!r} does not map to a single token for this tokenizer."
    )


class _SingleTokenCollator:
    def __init__(self, tokenizer, max_len, prompt_template):
        self.tokenizer = tokenizer
        self.max_len = max_len
        self.prompt_template = prompt_template

    def __call__(self, features):
        prompts = [self.prompt_template.format(text=f["text"]) for f in features]
        enc = self.tokenizer(
            prompts,
            padding=True,
            truncation=True,
            max_length=self.max_len,
            return_tensors="pt",
        )
        labels = torch.tensor([int(f["label"]) for f in features], dtype=torch.long)
        return {
            "input_ids": enc["input_ids"],
            "attention_mask": enc["attention_mask"],
            "labels": labels,
        }


class _SingleTokenTrainer(Trainer):
    """Cross-entropy over just the {true, false} class-token logits.

    Label convention: 1 = positive (metric holds) -> "true", 0 = negative -> "false".
    """

    def __init__(self, *args, true_id, false_id, **kwargs):
        super().__init__(*args, **kwargs)
        self.true_id = true_id
        self.false_id = false_id

    def _pair_logits(self, model, inputs):
        out = model(
            input_ids=inputs["input_ids"], attention_mask=inputs["attention_mask"]
        )
        last = out.logits[:, -1, :]
        return last[:, [self.true_id, self.false_id]]

    def compute_loss(self, model, inputs, return_outputs=False, **kwargs):
        pair = self._pair_logits(model, inputs)
        target = (1 - inputs["labels"]).long()  # positive(1)->col 0, negative(0)->col 1
        loss = F.cross_entropy(pair, target)
        return (loss, {"logits": pair}) if return_outputs else loss

    def prediction_step(self, model, inputs, prediction_loss_only, ignore_keys=None):
        labels = inputs["labels"]
        with torch.no_grad():
            pair = self._pair_logits(model, inputs)
            loss = F.cross_entropy(pair, (1 - labels).long())
        if prediction_loss_only:
            return (loss, None, None)
        return (loss, pair.float(), labels)


def _compute_metrics(eval_pred):
    pair, labels = eval_pred.predictions, eval_pred.label_ids
    probs = torch.softmax(torch.tensor(pair), dim=-1).numpy()
    p_positive = probs[:, 0]
    preds = (p_positive >= 0.5).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(
        labels, preds, average="binary", pos_label=1, zero_division=0
    )
    metrics = {
        "accuracy": float((preds == labels).mean()),
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
    }
    if len(np.unique(labels)) > 1:
        metrics["auroc"] = float(roc_auc_score(labels, p_positive))
    return metrics


def train_and_save(
    examples: List[Dict[str, Any]],
    name: str,
    description: str,
    base_model: str,
    output_dir: str,
    config: Dict[str, Any],
    status_path: Optional[str] = None,
) -> Dict[str, Any]:
    """Train a custom guardrail classifier and save a servable bundle.

    Returns the model name, its on-disk path, and the final eval metrics.
    """
    set_seed(config["seed"])

    prompt_template = build_prompt_template(description)

    tokenizer = AutoTokenizer.from_pretrained(base_model)
    tokenizer.padding_side = "left"
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    true_id = _resolve_class_token(tokenizer, "true")
    false_id = _resolve_class_token(tokenizer, "false")

    model = AutoModelForCausalLM.from_pretrained(base_model, torch_dtype=torch.bfloat16)
    model.config.pad_token_id = tokenizer.pad_token_id
    model = get_peft_model(
        model,
        LoraConfig(
            r=config["lora_r"],
            lora_alpha=config["lora_alpha"],
            lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj"],
            task_type="CAUSAL_LM",
        ),
    )

    dataset = Dataset.from_list(
        [{"text": e["text"], "label": int(e["label"])} for e in examples]
    ).shuffle(seed=config["seed"])
    holdout = dataset.train_test_split(
        test_size=config["test_fraction"], seed=config["seed"]
    )
    test_ds = holdout["test"]
    fit = holdout["train"].train_test_split(
        test_size=config["val_fraction"], seed=config["seed"]
    )
    train_ds, val_ds = fit["train"], fit["test"]

    collator = _SingleTokenCollator(tokenizer, config["max_len"], prompt_template)

    args = TrainingArguments(
        output_dir=os.path.join(output_dir, name, "_checkpoints"),
        num_train_epochs=config["epochs"],
        per_device_train_batch_size=config["batch_size"],
        per_device_eval_batch_size=config["batch_size"],
        learning_rate=config["lr"],
        warmup_ratio=0.03,
        weight_decay=0.01,
        bf16=torch.cuda.is_available(),
        logging_steps=20,
        eval_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="eval_validation_auroc",
        greater_is_better=True,
        report_to="none",
        remove_unused_columns=False,
        label_names=["labels"],
        seed=config["seed"],
    )

    trainer = _SingleTokenTrainer(
        model=model,
        args=args,
        train_dataset=train_ds,
        eval_dataset={"validation": val_ds, "test": test_ds},
        data_collator=collator,
        compute_metrics=_compute_metrics,
        true_id=true_id,
        false_id=false_id,
    )
    if status_path is not None:
        trainer.add_callback(_ProgressCallback(status_path))
    trainer.train()
    final_metrics = trainer.evaluate()

    model_dir = os.path.join(output_dir, name)
    adapter_dir = os.path.join(model_dir, "adapter")
    trainer.model.save_pretrained(adapter_dir)
    tokenizer.save_pretrained(adapter_dir)

    metric = {
        "name": name,
        "description": description,
        "prompt_template": prompt_template,
        "base_model": base_model,
        "threshold_default": 0.5,
        "eval_metrics": final_metrics,
    }
    with open(os.path.join(model_dir, "metric.json"), "w") as f:
        json.dump(metric, f, indent=2)

    return {"name": name, "path": model_dir, "metrics": final_metrics}
