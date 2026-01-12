"""Prompt placeholders for the GEPA optimizer.

FIXME: GEPA is its own package and manages its prompts internally (for example,
`reflection_prompt_template` in `gepa.optimize`). This optimizer does not expose
per-template prompt hooks yet, so prompt overrides are ignored. Treat GEPA
inputs (prompt, dataset, metric) and GEPA-level config as the customization
surface instead of internal templates.
"""

DEFAULT_PROMPTS: dict[str, str] = {
    "gepa_placeholder": "GEPA prompt overrides are not supported yet.",
}
