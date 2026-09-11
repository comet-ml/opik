"""Candidate assembly and selection helpers for GEPA."""

from __future__ import annotations

import logging
import re
from collections.abc import Iterable
from typing import Any

from ....utils.candidate import unique_ordered_by_key
from ....utils.scoring import improves_over
from ....api_objects.types import rebuild_content_with_new_text
from ....utils.toolcalling.core import segment_updates

logger = logging.getLogger(__name__)

TOOL_COMPONENT_PREFIX = segment_updates.TOOL_COMPONENT_PREFIX
TOOL_PARAM_COMPONENT_PREFIX = segment_updates.TOOL_PARAM_COMPONENT_PREFIX

# A ChatPrompt substitutes dataset values with a plain str.replace of
# "{" + key + "}" (api_objects/chat_prompt.get_messages), so a candidate that
# rewrites a message without the token drops the user's input *silently* — no
# KeyError, no warning, the model just answers blind. Hence the programmatic
# guard below rather than trusting the reflection LM to obey an instruction.
#
# The token shape is deliberately narrower than "anything in braces": it must
# look like an identifier (optionally dotted/hyphenated). Prompts routinely
# contain JSON or code samples (`{"a": 1}`, `{}`, `{ x }`), and treating those
# as variables would revert legitimate edits on every iteration and stall the
# whole optimization. Dataset keys outside this shape are protected only when
# the caller passes them in as ``known_keys`` — a key is authoritative
# regardless of shape, because substitution replaces its exact "{key}" text.
PLACEHOLDER_PATTERN = re.compile(r"\{([A-Za-z_][\w.\-]*)\}")


def dataset_placeholder_keys(items: Iterable[dict[str, Any]]) -> set[str]:
    """Union of the item keys — every one is substitutable as a "{key}" token."""
    return {key for item in items for key in item}


def _text_fragments(content: Any) -> list[str]:
    """Text pieces of a message content — the whole string, or the text parts
    of multimodal content (images/video carry no placeholders)."""
    if isinstance(content, str):
        return [content]
    if isinstance(content, list):
        return [
            str(part.get("text", ""))
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        ]
    return []


def extract_placeholders(content: Any, known_keys: set[str] | None = None) -> set[str]:
    """Return the template-variable tokens present in one message content.

    Tokens are identifier-shaped brace expressions, plus any ``known_keys``
    (dataset column names) whose exact "{key}" text appears — that literal is
    what substitution replaces, so a known key needs no particular shape.
    """
    tokens: set[str] = set()
    for text in _text_fragments(content):
        tokens.update(PLACEHOLDER_PATTERN.findall(text))
        for key in known_keys or ():
            if "{" + key + "}" in text:
                tokens.add(key)
    return tokens


def collect_placeholders(
    messages: list[dict[str, Any]], known_keys: set[str] | None = None
) -> set[str]:
    """Return every template-variable token across a prompt's messages."""
    tokens: set[str] = set()
    for message in messages:
        tokens.update(extract_placeholders(message.get("content", ""), known_keys))
    return tokens


def protected_tokens(content: Any, known_keys: set[str] | None = None) -> set[str]:
    """Return the tokens in one message that substitution would actually fill.

    When the caller knows the dataset's columns, those are authoritative: only
    a token naming a real column can receive a value, so `\\frac{num}{den}` or
    a literal `{TODO}` in a prompt is ordinary text and stays freely editable.
    Without that knowledge we fall back to the identifier shape and protect
    conservatively.
    """
    tokens = extract_placeholders(content, known_keys)
    if known_keys is not None:
        # An empty set is knowledge too: a dataset with no columns substitutes
        # nothing, so nothing in the prompt is a variable. Only ``None`` means
        # "columns unknown" and falls back to the identifier shape.
        return tokens & known_keys
    return tokens


def _protected_per_message(
    messages: list[dict[str, Any]], known_keys: set[str] | None
) -> list[set[str]]:
    return [
        protected_tokens(message.get("content", ""), known_keys) for message in messages
    ]


def _introduces_unseeded_column(
    new_tokens: set[str], seed_tokens: set[str], known_keys: set[str] | None
) -> bool:
    """Leakage: the edit references a dataset column the seed never used."""
    if known_keys is None:
        return False
    return bool((new_tokens & known_keys) - seed_tokens)


def _moved_elsewhere(
    token: str, seed_per_message: list[set[str]], new_per_message: list[set[str]]
) -> bool:
    """A token counts as moved only where the seed did not already carry it.

    Otherwise a stale duplicate left in another message would excuse deleting
    the one that actually holds the input.
    """
    return any(
        token in new_per_message[idx] and token not in seed_per_message[idx]
        for idx in range(len(new_per_message))
    )


def _drops_an_unmoved_variable(
    idx: int, seed_per_message: list[set[str]], new_per_message: list[set[str]]
) -> bool:
    """Loss: this message dropped a variable that went nowhere else."""
    return any(
        not _moved_elsewhere(token, seed_per_message, new_per_message)
        for token in seed_per_message[idx] - new_per_message[idx]
    )


def enforce_placeholder_preservation(
    *,
    original_messages: list[dict[str, Any]],
    new_messages: list[dict[str, Any]],
    prompt_name: str = "",
    known_keys: set[str] | None = None,
) -> tuple[list[dict[str, Any]], list[str]]:
    """Reject candidate edits that break the prompt's dataset-input contract.

    Two ways a candidate breaks it, both silent without this guard because
    substitution is a plain str.replace:

    * **Loss** — the edit drops a variable, so the model answers without the
      user's input. A variable the candidate genuinely *moved* to another
      message is not a loss: substitution runs over every message. "Moved"
      means some other message now carries the token where the seed did not —
      a stale duplicate elsewhere does not excuse deleting the real input slot.
    * **Leakage** — the edit introduces a dataset column the seed never used
      (typically the label, `{answer}`). Substitution would fill it in, the
      candidate would score against data it will not have at inference time,
      and it would win on a lie.

    Rejection is per-message: only offending messages revert to seed content,
    other edits in the same candidate survive. Reverting is then re-checked to
    a fixed point, because restoring one message's seed text also discards
    whatever the candidate had put there — including a variable it had moved
    in. Worst case every message reverts and the prompt equals the seed, so
    the loop always terminates with the contract intact.

    ``known_keys`` — the dataset's column names, when the caller has them —
    makes protection exact in both directions; see ``protected_tokens``.

    Returns the (possibly reverted) messages and the rejected component keys.
    """
    seed_per_message = _protected_per_message(original_messages, known_keys)
    seed_tokens: set[str] = (
        set().union(*seed_per_message) if seed_per_message else set()
    )

    guarded = list(new_messages)
    reverted: list[str] = []
    limit = min(len(original_messages), len(guarded))

    while True:
        new_per_message = _protected_per_message(guarded[:limit], known_keys)
        offending = [
            idx
            for idx in range(limit)
            if guarded[idx].get("content") != original_messages[idx].get("content", "")
            and (
                _introduces_unseeded_column(
                    new_per_message[idx], seed_tokens, known_keys
                )
                or _drops_an_unmoved_variable(idx, seed_per_message, new_per_message)
            )
        ]

        if not offending:
            return guarded, reverted

        for idx in offending:
            original = original_messages[idx]
            # Revert content only, keeping any other fields the message carries.
            guarded[idx] = {**original, "content": original.get("content", "")}
            reverted.append(f"{prompt_name}_{original.get('role')}_{idx}")


def build_seed_candidate(
    *,
    optimizable_prompts: dict[str, Any],
    allowed_roles: set[str] | None = None,
    tool_names: list[str] | None = None,
    enable_tools: bool = False,
) -> dict[str, str]:
    seed_candidate: dict[str, str] = {}
    for prompt_name, prompt_obj in optimizable_prompts.items():
        messages = prompt_obj.get_messages()
        for idx, msg in enumerate(messages):
            role = msg.get("role")
            if allowed_roles is not None and role not in allowed_roles:
                continue
            component_key = f"{prompt_name}_{msg['role']}_{idx}"
            content = msg.get("content", "")
            if isinstance(content, list):
                text_parts = [
                    part.get("text", "")
                    for part in content
                    if isinstance(part, dict) and part.get("type") == "text"
                ]
                content = " ".join(text_parts)
            seed_candidate[component_key] = str(content)
        if enable_tools:
            # Include tool description components when tool optimization is enabled.
            seed_candidate.update(
                segment_updates.build_tool_component_seed(
                    prompt_name=prompt_name,
                    prompt=prompt_obj,
                    tool_names=tool_names,
                )
            )
    return seed_candidate


def filter_duplicate_candidates(
    *,
    candidates: list[dict[str, str]],
    val_scores: list[float],
) -> tuple[list[dict[str, str]], list[float | None], list[tuple[int, dict[str, str]]]]:
    indexed_candidates: list[tuple[int, dict[str, str]]] = list(enumerate(candidates))
    filtered_indexed_candidates = unique_ordered_by_key(
        indexed_candidates,
        key=lambda item: str(sorted(item[1].items())),
    )
    filtered_candidates: list[dict[str, str]] = [
        candidate for _, candidate in filtered_indexed_candidates
    ]
    filtered_val_scores: list[float | None] = [
        val_scores[idx] if idx < len(val_scores) else None
        for idx, _ in filtered_indexed_candidates
    ]
    return filtered_candidates, filtered_val_scores, filtered_indexed_candidates


def select_best_candidate_index(
    *,
    rescored: list[float],
    filtered_val_scores: list[float | None],
    filtered_indexed_candidates: list[tuple[int, dict[str, str]]],
    initial_score: float,
    gepa_result: Any,
) -> tuple[int, float]:
    if rescored:

        def _tie_break(idx: int) -> tuple[float, float, int]:
            opik_score = rescored[idx]
            gepa_score = filtered_val_scores[idx]
            gepa_numeric = (
                float(gepa_score)
                if isinstance(gepa_score, (int, float))
                else float("-inf")
            )
            return opik_score, gepa_numeric, idx

        best_idx = max(range(len(rescored)), key=_tie_break)
        best_score = rescored[best_idx]
        # Tie policy (OPIK-7038): keep the seed unless a candidate STRICTLY beats
        # the baseline. A tie returns the -1 sentinel -> caller falls back to seed.
        if not improves_over(best_score, initial_score):
            return -1, float(initial_score)
        return best_idx, best_score

    if filtered_indexed_candidates:
        gepa_best_idx = getattr(gepa_result, "best_idx", 0) or 0
        best_idx = next(
            (
                i
                for i, (original_idx, _) in enumerate(filtered_indexed_candidates)
                if original_idx == gepa_best_idx
            ),
            0,
        )
        if filtered_val_scores and 0 <= best_idx < len(filtered_val_scores):
            score_value = filtered_val_scores[best_idx]
            best_score = float(score_value) if score_value is not None else 0.0
        else:
            best_score = float(initial_score)
        return best_idx, best_score

    return 0, float(initial_score)


def rebuild_prompts_from_candidate(
    *,
    base_prompts: dict[str, Any],
    candidate: dict[str, str],
    allowed_roles: set[str] | None = None,
    known_placeholder_keys: set[str] | None = None,
) -> tuple[dict[str, Any], list[str]]:
    """Rebuild prompts with optimized messages from a GEPA candidate.

    Every rebuild runs through ``enforce_placeholder_preservation``, so the
    guard's rules apply here as they do on the adapter's evaluate() path:

    * A token counts as a template variable if it is identifier-shaped
      (``{question}``) or names a dataset column passed in
      ``known_placeholder_keys``. Arbitrary brace text (JSON, code samples)
      is deliberately not protected — see ``PLACEHOLDER_PATTERN``.
    * Comparison is prompt-wide, so a variable a candidate merely *moved*
      between messages is kept: substitution still reaches it.
    * Only the messages that both changed and carried a now-missing token are
      reverted to seed content. Other edits in the same candidate survive.

    Returns the rebuilt prompts and the component keys whose candidate edit
    the placeholder guard rejected (empty when the candidate was clean).
    """
    rebuilt: dict[str, Any] = {}
    placeholder_reverts: list[str] = []
    for prompt_name, prompt_obj in base_prompts.items():
        original_messages = prompt_obj.get_messages()
        new_messages = []
        for idx, msg in enumerate(original_messages):
            component_key = f"{prompt_name}_{msg['role']}_{idx}"
            original_content = msg.get("content", "")
            optimized_text = candidate.get(component_key)

            if optimized_text is not None and (
                allowed_roles is None or msg.get("role") in allowed_roles
            ):
                new_content = rebuild_content_with_new_text(
                    original_content, optimized_text
                )
            else:
                new_content = original_content

            new_messages.append({"role": msg["role"], "content": new_content})

        new_messages, reverted = enforce_placeholder_preservation(
            original_messages=original_messages,
            new_messages=new_messages,
            prompt_name=prompt_name,
            known_keys=known_placeholder_keys,
        )
        placeholder_reverts.extend(reverted)

        new_prompt = prompt_obj.copy()
        new_prompt.set_messages(new_messages)
        new_prompt = segment_updates.apply_tool_updates_from_candidate(
            candidate=candidate,
            prompt=new_prompt,
            tool_component_prefix=f"{prompt_name}{TOOL_COMPONENT_PREFIX}",
            tool_param_component_prefix=f"{prompt_name}{TOOL_PARAM_COMPONENT_PREFIX}",
        )
        rebuilt[prompt_name] = new_prompt
    if placeholder_reverts:
        logger.warning(
            "Rejected GEPA candidate edit(s) %s: the rewrite dropped template "
            "variable(s) present in the seed prompt. Reverted to seed content so "
            "the candidate is never evaluated with the user's input missing.",
            placeholder_reverts,
        )
    return rebuilt, placeholder_reverts


def count_disallowed_candidate_components(
    candidate: dict[str, str],
    allowed_roles: set[str] | None,
) -> int:
    """Count candidate components that target disallowed roles."""
    if allowed_roles is None:
        return 0
    if not allowed_roles:
        return sum(1 for key in candidate.keys() if key)
    count = 0
    for key in candidate.keys():
        if TOOL_COMPONENT_PREFIX in key or TOOL_PARAM_COMPONENT_PREFIX in key:
            continue
        parts = key.rsplit("_", 2)
        if len(parts) != 3:
            continue
        role = parts[1]
        if role not in allowed_roles:
            count += 1
    return count
