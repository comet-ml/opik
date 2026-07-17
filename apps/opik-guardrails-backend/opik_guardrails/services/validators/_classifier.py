"""Shared inference for the single-token true/false classifiers (prompt injection
and custom trained guardrails). Both fine-tune a decoder LM into a classifier whose
answer is a single `true`/`false` token, so scoring is identical; only how the model
is loaded and which prompt template it uses differ.
"""

import transformers
import torch


def resolve_class_token(tokenizer: transformers.PreTrainedTokenizer, word: str) -> int:
    """Return the single vocab-token id for a class word, else raise."""
    for candidate in (f" {word}", word):
        ids = tokenizer.encode(candidate, add_special_tokens=False)
        if len(ids) == 1:
            return ids[0]
    raise ValueError(
        f"Class word {word!r} does not map to a single token for this tokenizer."
    )


def positive_class_score(
    model: torch.nn.Module,
    tokenizer: transformers.PreTrainedTokenizer,
    true_id: int,
    false_id: int,
    device: str,
    text: str,
    prompt_template: str,
) -> float:
    """Probability of the positive ("true") class for a piece of text.

    The softmax is taken over just the two class-token logits at the final
    position, matching how the classifier was trained.
    """
    prompt = prompt_template.format(text=text)
    inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=512).to(
        device
    )

    with torch.no_grad():
        logits = model(**inputs).logits[:, -1, :]

    pair = logits[:, [true_id, false_id]]
    return float(torch.softmax(pair, dim=-1)[0, 0])
