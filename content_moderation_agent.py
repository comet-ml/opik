from dotenv import load_dotenv
load_dotenv(".env.local")

import os
os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:5174/api/"

import json
import opik
from dataclasses import dataclass, field
from typing import TypedDict, Optional

from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage
from opik.integrations.langchain import OpikTracer

from flask import Flask, jsonify, request
from opik_config import Prompt, experiment_context, agent_config


# ============================================================================
# Configuration
# ============================================================================

@agent_config
@dataclass
class ModerationConfig:
    """Configuration for the content moderation agent."""

    # Scalar knob for precision/recall tradeoff
    risk_tolerance: float = field(default=0.5)

    # Prompt A - Extract risky elements (high recall)
    extract_system_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Extract Risk Elements",
        prompt="""You are a content moderation analyst. Your task is to identify ALL potentially policy-relevant elements in the given text.

IMPORTANT: Prioritize HIGH RECALL. Include borderline cases - it's better to flag something that turns out to be fine than to miss something harmful.

You must return ONLY valid JSON with this exact structure:
{
  "risk_items": [
    {
      "category": "<one of: hate, harassment, self_harm, sexual, violence, pii, scam>",
      "span": "<exact verbatim substring from the input text>",
      "why": "<brief explanation of why this is potentially risky>",
      "confidence": <float 0-1, how confident you are this is a policy violation>
    }
  ]
}

Categories:
- hate: Content expressing hatred toward protected groups
- harassment: Targeted attacks, bullying, threats against individuals
- self_harm: Content promoting or glorifying self-injury or suicide
- sexual: Explicit sexual content or solicitation
- violence: Graphic violence, threats, glorification of violence
- pii: Personal identifiable information (emails, phones, addresses, SSNs)
- scam: Fraud, phishing, deceptive schemes

Rules:
1. The "span" MUST be an exact verbatim substring from the input
2. Include items even with low confidence if they could be policy-relevant
3. If no risks found, return {"risk_items": []}
4. Return ONLY the JSON object, no other text"""
    ))

    extract_user_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Extract User Prompt",
        prompt="""Analyze this content for policy violations:

CONTENT:
{content}

CONTEXT:
- Channel: {channel}
- Region: {region}
- Prior violations: {prior_violations}

Extract all potentially risky elements. Remember: high recall is critical."""
    ))

    # Prompt B - Score severity (calibrated scoring)
    score_system_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Score Severity",
        prompt="""You are a content moderation scoring system. Given extracted risk items, you must produce calibrated severity scores.

You must return ONLY valid JSON with this exact structure:
{
  "scores": {
    "hate": <float 0-1>,
    "harassment": <float 0-1>,
    "self_harm": <float 0-1>,
    "sexual": <float 0-1>,
    "violence": <float 0-1>,
    "pii": <float 0-1>,
    "scam": <float 0-1>,
    "overall": <float 0-1>,
    "uncertainty": <float 0-1>
  },
  "notes_for_audit": "<brief neutral notes about the scoring rationale>"
}

Scoring guidelines:
- 0.0-0.2: No concern or clearly benign
- 0.2-0.4: Minor concern, likely acceptable
- 0.4-0.6: Moderate concern, borderline
- 0.6-0.8: Significant concern, likely violation
- 0.8-1.0: Severe violation, clear policy breach

The "overall" score should reflect COMBINED risk considering:
- Severity of the worst item
- Number of violations
- Interaction effects between categories
- NOT simply max(category_scores)

The "uncertainty" score reflects how confident you are in the overall assessment:
- High uncertainty (>0.7) when: ambiguous context, sarcasm/satire unclear, cultural nuance
- Low uncertainty (<0.3) when: clear-cut violations or clearly safe content

Return ONLY the JSON object, no other text."""
    ))

    score_user_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Score User Prompt",
        prompt="""Score the severity of these extracted risk items:

ORIGINAL CONTENT:
{content}

EXTRACTED RISK ITEMS:
{risk_items}

CONTEXT:
- Channel: {channel}
- Region: {region}
- Prior violations: {prior_violations}

Provide calibrated severity scores."""
    ))

    # Prompt C - Explain decision (no control authority)
    explain_system_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Explain Decision",
        prompt="""You are a content moderation explanation generator. Given the moderation decision that has ALREADY been made, provide a clear rationale.

IMPORTANT: You do NOT choose the action. The action has already been determined. Your job is only to explain it.

You must return ONLY valid JSON with this exact structure:
{
  "mod_notes": "<1-3 sentences explaining the decision rationale>",
  "priority": "<one of: low, medium, high>",
  "user_message": "<optional message to show the user, or empty string>"
}

Priority guidelines:
- low: ALLOW decisions, minor WARNs
- medium: WARN decisions, standard REMOVEs
- high: ESCALATE decisions, severe content

User message guidelines:
- For ALLOW: leave empty
- For WARN: brief, educational message about community guidelines
- For REMOVE: clear statement that content was removed and why
- For ESCALATE: leave empty (human reviewer will handle)

Return ONLY the JSON object, no other text."""
    ))

    explain_user_prompt: Prompt = field(default_factory=lambda: Prompt(
        name="Explain User Prompt",
        prompt="""Generate an explanation for this moderation decision:

CONTENT:
{content}

DECISION: {decision}

SCORES:
{scores}

RISK ITEMS:
{risk_items}

THRESHOLDS USED:
- Warn threshold: {t_warn}
- Remove threshold: {t_remove}
- Escalate threshold: {t_escalate}

Explain why this decision was made."""
    ))


config = ModerationConfig()


# ============================================================================
# Deterministic Policy Logic
# ============================================================================

def compute_thresholds(risk_tolerance: float) -> dict:
    """Compute action thresholds from risk_tolerance scalar."""
    return {
        "t_warn": 0.25 + 0.25 * risk_tolerance,
        "t_remove": 0.45 + 0.35 * risk_tolerance,
        "t_escalate": 0.65 + 0.25 * risk_tolerance,
    }


def determine_action(
    overall_score: float,
    uncertainty: float,
    prior_violations: int,
    thresholds: dict
) -> str:
    """
    Deterministic action mapping based on scores and thresholds.

    Returns one of: ALLOW, WARN, REMOVE, ESCALATE
    """
    t_warn = thresholds["t_warn"]
    t_remove = thresholds["t_remove"]
    t_escalate = thresholds["t_escalate"]

    # Base action from overall score
    if overall_score < t_warn:
        action = "ALLOW"
    elif overall_score < t_remove:
        action = "WARN"
    elif overall_score < t_escalate:
        action = "REMOVE"
    else:
        action = "ESCALATE"

    # Uncertainty override: high uncertainty + moderate risk -> escalate
    if uncertainty >= 0.70 and overall_score >= t_remove:
        action = "ESCALATE"

    # Prior violations override: repeat offenders get harsher treatment
    if prior_violations >= 3 and action in ("ALLOW", "WARN"):
        action = "WARN" if action == "ALLOW" else "REMOVE"

    return action


# ============================================================================
# LLM Helpers
# ============================================================================

def call_llm_json(system_prompt: str, user_prompt: str) -> dict:
    """Call LLM and parse JSON response."""
    tracer = OpikTracer()
    llm = ChatOpenAI(
        model="gpt-4o-mini",
        temperature=0.1,
        callbacks=[tracer],
    )

    messages = [
        SystemMessage(content=system_prompt),
        HumanMessage(content=user_prompt),
    ]

    response = llm.invoke(messages)
    content = response.content.strip()

    # Handle markdown code blocks
    if content.startswith("```"):
        lines = content.split("\n")
        content = "\n".join(lines[1:-1])

    return json.loads(content)


# ============================================================================
# Main Moderation Function
# ============================================================================

@opik.track(name="content_moderation", project_name="content_moderation_agent")
def moderate_content(
    content: str,
    context: Optional[dict] = None,
    risk_tolerance: Optional[float] = None
) -> dict:
    """
    Run the 3-stage content moderation pipeline.

    Args:
        content: The text to moderate
        context: Optional dict with channel, region, prior_violations
        risk_tolerance: Override for the risk_tolerance scalar (0.0-1.0)

    Returns:
        Moderation result with risk_items, scores, decision, explanation, debug
    """
    # Defaults
    context = context or {}
    channel = context.get("channel", "unknown")
    region = context.get("region", "unknown")
    prior_violations = context.get("prior_violations", 0)

    # Use provided risk_tolerance or fall back to config
    tolerance = risk_tolerance if risk_tolerance is not None else config.risk_tolerance
    thresholds = compute_thresholds(tolerance)

    print(f"\n[Moderation] Processing content ({len(content)} chars)")
    print(f"[Moderation] risk_tolerance={tolerance}, thresholds={thresholds}")

    # -------------------------------------------------------------------------
    # Stage A: Extract risky elements
    # -------------------------------------------------------------------------
    print("[Moderation] Stage A: Extracting risk items...")

    extract_user = config.extract_user_prompt.prompt.format(
        content=content,
        channel=channel,
        region=region,
        prior_violations=prior_violations,
    )

    extract_result = call_llm_json(
        config.extract_system_prompt.prompt,
        extract_user
    )
    risk_items = extract_result.get("risk_items", [])
    print(f"[Moderation] Found {len(risk_items)} risk items")

    # -------------------------------------------------------------------------
    # Stage B: Score severity
    # -------------------------------------------------------------------------
    print("[Moderation] Stage B: Scoring severity...")

    score_user = config.score_user_prompt.prompt.format(
        content=content,
        risk_items=json.dumps(risk_items, indent=2),
        channel=channel,
        region=region,
        prior_violations=prior_violations,
    )

    score_result = call_llm_json(
        config.score_system_prompt.prompt,
        score_user
    )
    scores = score_result.get("scores", {})
    notes_for_audit = score_result.get("notes_for_audit", "")

    overall = scores.get("overall", 0.0)
    uncertainty = scores.get("uncertainty", 0.0)
    print(f"[Moderation] overall={overall:.3f}, uncertainty={uncertainty:.3f}")

    # -------------------------------------------------------------------------
    # Deterministic decision
    # -------------------------------------------------------------------------
    decision = determine_action(overall, uncertainty, prior_violations, thresholds)
    print(f"[Moderation] Decision: {decision}")

    # -------------------------------------------------------------------------
    # Stage C: Explain decision
    # -------------------------------------------------------------------------
    print("[Moderation] Stage C: Generating explanation...")

    explain_user = config.explain_user_prompt.prompt.format(
        content=content,
        decision=decision,
        scores=json.dumps(scores, indent=2),
        risk_items=json.dumps(risk_items, indent=2),
        t_warn=thresholds["t_warn"],
        t_remove=thresholds["t_remove"],
        t_escalate=thresholds["t_escalate"],
    )

    explain_result = call_llm_json(
        config.explain_system_prompt.prompt,
        explain_user
    )

    mod_notes = explain_result.get("mod_notes", "")
    priority = explain_result.get("priority", "medium")
    user_message = explain_result.get("user_message", "")

    print(f"[Moderation] Complete. Priority: {priority}")

    # -------------------------------------------------------------------------
    # Build final output
    # -------------------------------------------------------------------------
    return {
        "risk_items": risk_items,
        "scores": scores,
        "decision": decision,
        "explanation": {
            "mod_notes": mod_notes,
            "priority": priority,
            "user_message": user_message,
            "notes_for_audit": notes_for_audit,
        },
        "debug": {
            "risk_tolerance": tolerance,
            "thresholds": thresholds,
            "overall_score": overall,
            "uncertainty": uncertainty,
            "prior_violations": prior_violations,
            "num_risk_items": len(risk_items),
        }
    }


# ============================================================================
# Flask API
# ============================================================================

app = Flask(__name__)


@app.route("/health")
def health():
    """Health check endpoint."""
    return {"status": "ok"}


@app.route("/chat", methods=["POST"])
def chat():
    """
    Main moderation endpoint.

    Expects JSON:
    {
        "message": "text to moderate"
    }

    Returns JSON: Full moderation result
    """
    experiment_id = request.headers.get("X-Opik-Experiment-Id")
    print(f"\n[API] ========== INCOMING REQUEST ==========")
    print(f"[API] X-Opik-Experiment-Id header: {experiment_id}")
    print(f"[API] Request JSON: {request.json}")

    with experiment_context(request):
        content = request.json.get("message", "")
        result = moderate_content(content)

    return jsonify({"response": result})


if __name__ == "__main__":
    app.run(port=8001)
