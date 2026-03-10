from __future__ import annotations

import logging
import re
from collections.abc import Mapping, Sequence
from typing import Any

logger = logging.getLogger(__name__)

_TEMPLATE_VAR_RE = re.compile(r"\{(\w+)\}")

GENERALIZATION_REFLECTION_TEMPLATE = (
    "I have a system that uses the following parameter to guide its behavior:\n"
    "```\n"
    "<curr_param>\n"
    "```\n"
    "\n"
    "The following are examples of inputs along with the system's outputs and "
    "feedback showing which assertions PASSED and which FAILED. "
    "Examples are sorted by priority — the ones with the most failures come first:\n"
    "```\n"
    "<side_info>\n"
    "```\n"
    "\n"
    "Your task is to write an improved version of this parameter that fixes "
    "the FAILED assertions while preserving PASSED ones. "
    "Use the existing parameter as your starting point.\n"
    "\n"
    "STEP 1 — DIAGNOSE:\n"
    "Read the FAILED assertions and identify what specific behaviors are missing or wrong. "
    "Read the PASSED assertions — the current parameter already produces these. "
    "Prefer keeping rules that drive successes, but you may tighten or rephrase them "
    "if needed to fix failures.\n"
    "\n"
    "STEP 2 — CHECK FAILURE HISTORY:\n"
    "If any example has a \"Failure History\" section, the listed assertions have been "
    "persistently failing across many previous attempts to fix them "
    "(the failure count out of total evaluations is shown). "
    "The existing rules for these assertions are not working — "
    "do NOT refine or rephrase them. Instead, try a fundamentally different approach:\n"
    "(a) restructure the parameter so the failing behavior gets more prominent placement;\n"
    "(b) add explicit step-by-step procedures with concrete example phrases;\n"
    "(c) add conditional logic (\"When X, always do Y before Z\");\n"
    "(d) rewrite the section governing the failing behavior from scratch.\n"
    "For persistent failures, you may use more specific and detailed rules "
    "than you normally would, but still avoid copying non-generalizable "
    "specifics from the feedback examples.\n"
    "\n"
    "STEP 3 — WRITE FIXES:\n"
    "For each failing assertion, first check whether an existing rule can be "
    "updated to cover the failing behavior before adding a new one. "
    "You may change multiple related rules together if the failure requires "
    "coordinated changes. Every rule must describe an observable, verifiable action — "
    "not abstract guidance. Rules must generalize to any input. "
    "NEVER copy specific names, details, or scenarios from the feedback "
    "examples into the parameter — they are just samples and will change "
    "at runtime. Abstract them into general categories.\n"
    "\n"
    "STEP 4 — STRUCTURE:\n"
    "Use markdown formatting. Group rules by behavior pattern under ## headers, "
    "not by scenario type. Merge overlapping rules. Keep the parameter concise.\n"
    "\n"
    "IMPORTANT:\n"
    "- Output ONLY the parameter text. Do NOT include any metadata such as "
    "\"Parameter:\", \"Description:\", or \"Other parameters\" lines — "
    "those are context for you, not part of the parameter.\n"
    "- Preserve ALL template variables exactly as they appear in the original "
    "parameter (e.g. {{var}}, {var}, <var>, {% var %}). These are runtime "
    "placeholders filled by the system — do NOT rename, remove, or reformat them. "
    "If the original has {var}, your output MUST also contain {var}.\n"
    "\n"
    "Provide the new parameter within ``` blocks."
)


class ReflectionProposer:
    """Proposes improved prompt texts using a reflection LLM.

    Wraps the GEPA InstructionProposalSignature with parameter-name prefixing
    and full reflection logging for debugging.
    """

    def __init__(
        self,
        reflection_lm: Any,
        reflection_prompt_template: str | None = None,
        config_descriptions: dict[str, str] | None = None,
    ) -> None:
        self._reflection_lm = reflection_lm
        self._reflection_prompt_template = reflection_prompt_template
        self._config_descriptions = config_descriptions or {}
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

    def _strip_header(self, text: str, name: str) -> str:
        """Strip header metadata lines from the beginning of proposed text.

        The LLM sometimes echoes the header (Parameter:, Description:, Other
        parameters:) at the start of its output, sometimes reformulated. This
        method strips those lines so they don't leak into the proposal.
        """
        lines = text.split("\n")
        description = self._config_descriptions.get(name, "")

        # Strip leading lines that look like header metadata
        while lines:
            stripped = lines[0].strip()
            if not stripped:
                lines.pop(0)
                continue
            if stripped.lower().startswith("parameter:"):
                lines.pop(0)
                continue
            if stripped.lower().startswith("description:"):
                lines.pop(0)
                continue
            if stripped.lower().startswith("other parameters"):
                lines.pop(0)
                continue
            # Bullet items from the sibling list (e.g., "- user_message: ...")
            if stripped.startswith("- ") and ":" in stripped:
                candidate_key = stripped[2:].split(":")[0].strip()
                if candidate_key in (self._config_descriptions or {}):
                    lines.pop(0)
                    continue
            # Bare parameter name (e.g., "system_prompt" or "System Prompt")
            if stripped.lower().replace("_", " ") == name.lower().replace("_", " "):
                lines.pop(0)
                continue
            # Description text echoed without "Description:" prefix
            if description and stripped == description:
                lines.pop(0)
                continue
            break

        return "\n".join(lines).strip()

    def _build_header(self, name: str, candidate: dict[str, str]) -> str:
        """Build the parameter header with optional description and sibling context."""
        header = f"Parameter: {name}"
        description = self._config_descriptions.get(name, "")
        if description:
            header += f"\nDescription: {description}"

        others = [k for k in candidate if k != name]
        if others:
            header += "\n\nOther parameters in this system (for context only — do NOT modify these):"
            for other in others:
                other_desc = self._config_descriptions.get(other, "")
                if other_desc:
                    header += f"\n- {other}: {other_desc}"
                else:
                    header += f"\n- {other}"

        return header

    @staticmethod
    def _extract_template_vars(text: str) -> set[str]:
        return set(_TEMPLATE_VAR_RE.findall(text))

    def _validate_template_vars(
        self, original: str, proposed: str, name: str,
    ) -> str | None:
        """Return proposed text if template vars are preserved, else None."""
        original_vars = self._extract_template_vars(original)
        if not original_vars:
            return proposed
        proposed_vars = self._extract_template_vars(proposed)
        missing = original_vars - proposed_vars
        if missing:
            logger.warning(
                "Reflection for '%s' dropped template variables %s — rejecting proposal",
                name, missing,
            )
            return None
        return proposed

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

            header = self._build_header(name, candidate)
            current_instruction = f"{header}\n{candidate[name]}"
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
            new_text = self._strip_header(new_text, name)

            validated = self._validate_template_vars(candidate[name], new_text, name)
            if validated is None:
                log_entry["proposed_text"] = new_text
                log_entry["rejected"] = "missing_template_vars"
                self._reflection_log.append(log_entry)
                continue

            new_texts[name] = validated

            log_entry["proposed_text"] = validated
            self._reflection_log.append(log_entry)
            logger.info(
                "Reflection for '%s': proposed %d chars (was %d)",
                name, len(validated), len(candidate[name]),
            )

        return new_texts
