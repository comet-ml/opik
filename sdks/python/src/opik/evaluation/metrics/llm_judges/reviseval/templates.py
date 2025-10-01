from __future__ import annotations

from typing import List, Optional


def build_prompt(
    question: str,
    answer: str,
    context: Optional[List[str]],
) -> str:
    context_section = (
        "\n".join(f"- {snippet}" for snippet in (context or []))
        or "(no external context provided)"
    )
    return f"""You are RevisEval, a grounded evaluation judge. Assess whether the MODEL_ANSWER is factually grounded in the CONTEXT and fully answers the QUESTION. Cite supporting or contradicting evidence from the context.
QUESTION:
{question}

CONTEXT:
{context_section}

MODEL_ANSWER:
{answer}

Respond with strict JSON containing keys: "score" (float 0-1), "reason" (string explanation referencing context evidence).
"""
