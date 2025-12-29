"""Lightweight LiteLLM agent that injects ARC-AGI images into messages."""

from __future__ import annotations

from typing import Any, Iterable, Sequence

from opik_optimizer.agents.litellm_agent import LiteLLMAgent

from .logging_utils import debug_print


def _first_nonempty(values: Iterable[str | None]) -> str | None:
    for v in values:
        if isinstance(v, str) and v.strip():
            return v
    return None


class ArcAgiImageAgent(LiteLLMAgent):
    """
    Extends ``LiteLLMAgent`` to attach ARC-AGI train/test images to the prompt.

    The dataset already carries base64 PNG data in keys such as
    ``train_input_image_color`` and ``test_input_image_color``. When those
    fields are present, this agent appends additional user messages with
    ``image_url`` parts so the LLM can see the visual grids alongside the text.
    """

    def __init__(
        self, project_name: str, include_images: bool = True, debug_log: bool = False
    ) -> None:
        super().__init__(project_name=project_name)
        self.include_images = include_images
        self.debug_log = debug_log

    def _prepare_messages(
        self, messages: list[dict[str, Any]], dataset_item: dict[str, Any] | None
    ) -> list[dict[str, Any]]:
        if not self.include_images or not dataset_item:
            return messages

        def _get_list(keys: Sequence[str]) -> list[str | None]:
            for key in keys:
                vals = dataset_item.get(key)
                if isinstance(vals, list) and vals:
                    return vals
            return []

        # Try a few likely key variants; dataset loader populates the first set.
        train_inputs = _get_list(
            [
                "train_images",  # canonical key from dataset loader
                "train_input_image_color",
                "train_input_image_annotated",
                "train_input_images",
            ]
        )
        train_outputs = _get_list(
            [
                "train_output_images",  # canonical key from dataset loader
                "train_output_image_color",
                "train_output_image_annotated",
                "train_images_output",
            ]
        )
        test_inputs = _get_list(
            [
                "test_images",  # canonical key from dataset loader
                "test_input_image_color",
                "test_input_image_annotated",
                "test_input_images",
            ]
        )

        augmented: list[dict[str, Any]] = list(messages)

        # Attach train examples
        max_train = max(len(train_inputs), len(train_outputs))
        for idx in range(max_train):
            inp = train_inputs[idx] if idx < len(train_inputs) else None
            out = train_outputs[idx] if idx < len(train_outputs) else None
            if not _first_nonempty([inp, out]):
                continue
            content_parts: list[dict[str, Any]] = []
            if inp:
                content_parts.append(
                    {"type": "text", "text": f"Train example {idx} input (image)"}
                )
                content_parts.append(
                    {"type": "image_url", "image_url": {"url": inp, "detail": "high"}}
                )
            if out:
                content_parts.append(
                    {"type": "text", "text": f"Train example {idx} output (image)"}
                )
                content_parts.append(
                    {"type": "image_url", "image_url": {"url": out, "detail": "high"}}
                )
            if content_parts:
                augmented.append({"role": "user", "content": content_parts})

        # Attach test inputs (one message per test grid)
        for idx, test_img in enumerate(test_inputs):
            if not test_img:
                continue
            augmented.append(
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": f"Test input {idx} (image). Return output grids for all test inputs.",
                        },
                        {
                            "type": "image_url",
                            "image_url": {"url": test_img, "detail": "high"},
                        },
                    ],
                }
            )

        if self.debug_log:
            debug_print(
                f"[image_agent] attached train_imgs={len(train_inputs)} train_out_imgs={len(train_outputs)} test_imgs={len(test_inputs)}",
                True,
            )

        return augmented
