from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from typing import Any

logger = logging.getLogger(__name__)

GENERALIZATION_REFLECTION_TEMPLATE = """\
I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant \
along with the assistant's response for each of them, and feedback showing \
which assertions PASSED and which FAILED. \
Examples are sorted by priority — the ones with the most failures come first:
```
<side_info>
```

Your task is to write an improved instruction. Preserve working rules and \
make targeted additions or tweaks to fix the FAILED assertions.

STEP 1 — DIAGNOSE: Read the FAILED assertions and identify what behaviors \
are missing. Read the PASSED assertions — the current instruction already \
produces these. Preserve the rules that drive successes.

STEP 2 — CHECK FAILURE HISTORY: If any example has a "Failure History" \
section, the current rules for that assertion already failed before. \
Do NOT add another generic rule of the same kind. Instead embed concrete \
example phrases or lookup instructions directly, or try a structurally \
different approach.

STEP 3 — WRITE TARGETED FIXES: For each failing assertion, add or modify \
a specific rule. Every rule must describe an observable action (what to say, \
include, or avoid) — abstract advice like "be empathetic" does not reliably \
work. Rules must generalize to any input in this domain; do NOT reference \
specific test inputs.

STEP 4 — STRUCTURE: Group related rules under short topic headers \
(e.g., "## Empathy", "## Resolution", "## Policy"). Merge overlapping \
rules. Remove redundant ones. Keep the instruction concise — prefer \
tightening existing rules over appending new ones.

Provide the new instructions within ``` blocks."""


class ReflectionProposer:
    """Proposes improved prompt texts using a reflection LLM.

    Wraps the GEPA InstructionProposalSignature with parameter-name prefixing
    and full reflection logging for debugging.
    """

    def __init__(
        self,
        reflection_lm: Any,
        reflection_prompt_template: str | None = None,
    ) -> None:
        self._reflection_lm = reflection_lm
        self._reflection_prompt_template = reflection_prompt_template
        self._reflection_log: list[dict[str, Any]] = []

    @property
    def reflection_log(self) -> list[dict[str, Any]]:
        return self._reflection_log

    @property
    def reflection_prompt_template(self) -> str | None:
        return self._reflection_prompt_template

    def _get_lm_callable(self) -> Any:
        """Return a callable LanguageModel from the stored reflection_lm."""
        if callable(self._reflection_lm):
            return self._reflection_lm
        if isinstance(self._reflection_lm, str):
            import litellm
            model_name = self._reflection_lm

            def _lm(prompt: str | list[dict[str, str]]) -> str:
                if isinstance(prompt, str):
                    completion = litellm.completion(
                        model=model_name,
                        messages=[{"role": "user", "content": prompt}],
                    )
                else:
                    completion = litellm.completion(model=model_name, messages=prompt)
                return completion.choices[0].message.content
            return _lm
        raise ValueError(f"reflection_lm must be a string or callable, got {type(self._reflection_lm)}")

    def propose(
        self,
        candidate: dict[str, str],
        reflective_dataset: Mapping[str, Sequence[Mapping[str, Any]]],
        components_to_update: list[str],
    ) -> dict[str, str]:
        """Propose improved texts for each component using the reflection LLM."""
        from gepa.strategies.instruction_proposal import InstructionProposalSignature

        lm = self._get_lm_callable()
        new_texts: dict[str, str] = {}
        for name in components_to_update:
            if name not in reflective_dataset or not reflective_dataset.get(name):
                logger.info("Component '%s' not in reflective dataset, skipping.", name)
                continue

            current_instruction = f"Parameter: {name}\n{candidate[name]}"
            dataset_with_feedback = reflective_dataset[name]

            input_dict = {
                "current_instruction_doc": current_instruction,
                "dataset_with_feedback": dataset_with_feedback,
                "prompt_template": self._reflection_prompt_template,
            }

            rendered_prompt = InstructionProposalSignature.prompt_renderer(input_dict)

            log_entry: dict[str, Any] = {
                "component": name,
                "current_instruction": current_instruction,
                "dataset_with_feedback": [dict(d) for d in dataset_with_feedback],
                "rendered_prompt": rendered_prompt if isinstance(rendered_prompt, str) else str(rendered_prompt),
            }

            result = InstructionProposalSignature.run(
                lm=lm,
                input_dict=input_dict,
            )
            new_text = result["new_instruction"]
            prefix = f"Parameter: {name}\n"
            if new_text.startswith(prefix):
                new_text = new_text[len(prefix):]
            new_texts[name] = new_text

            log_entry["proposed_text"] = new_text
            self._reflection_log.append(log_entry)
            logger.info(
                "Reflection for '%s': proposed %d chars (was %d)",
                name, len(new_text), len(candidate[name]),
            )

        return new_texts
