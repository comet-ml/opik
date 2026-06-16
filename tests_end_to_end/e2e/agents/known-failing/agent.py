"""Known-failing golden agent (for the Ollie /improve loop).

An instrumented agent whose answer format is governed by an externalized system
prompt (`SYSTEM_PROMPT`) — the knob the Ollie /improve flow tunes by editing the
source. The baseline prompt under-specifies the output (it never asks for
units), so the agent fails the unit-checked half of a fixed evaluation suite.
A more explicit prompt (the kind /improve proposes — "always include units")
raises the pass rate.

The "LLM" here is a deterministic stand-in that honours the prompt's
instructions rather than a real model call — this keeps the agent offline and
the /improve pass-rate change reproducible in CI. The assertion that matters is
the *direction* of the pass rate, not any model's wording.

Entrypoint + type hints are present for Local Runner (`opik connect`) schema
discovery.
"""

import opik

# Externalized, tunable system prompt — the knob the Ollie /improve flow turns.
# Baseline: it does not ask for units, so the unit-checked suite cases fail.
SYSTEM_PROMPT = "Answer the question."


@opik.track(type="tool", name="lookup")
def lookup(question: str) -> str:
    table = {
        "distance earth to moon": "384400",
        "boiling point of water": "100",
        "speed of light": "299792458",
        "freezing point of water": "0",
        "number of continents": "7",
        "days in a week": "7",
    }
    return table.get(question.strip().lower(), "unknown")


# Signals in the system prompt that should make the agent append units. Kept
# broad on purpose: the Ollie /improve flow may word its fix many ways
# ("include units", "give a complete answer", "be precise with measurements"),
# and the directional pass-rate test must hold for any reasonable improvement,
# not just the literal word "unit".
_UNIT_SIGNALS = ("unit", "complete", "precise", "full", "measurement", "exact")


@opik.track(type="llm", name="format-answer")
def format_answer(system_prompt: str, value: str, units: str) -> str:
    # Deterministic prompt-follower: appends units when the prompt asks for a
    # more complete/precise answer. The baseline prompt does not, so the
    # unit-required cases fail until /improve clarifies the prompt.
    sp = system_prompt.lower()
    if units and any(sig in sp for sig in _UNIT_SIGNALS):
        return f"{value} {units}"
    return value


# The agent's own data: the unit for each question (empty = no unit). Some
# questions carry a unit (these fail on the baseline prompt, which omits units),
# some don't (they pass regardless) — so the baseline lands at a partial pass
# rate that /improve can lift, rather than an all-or-nothing 0%. This is the
# standalone agent's data; suite.json holds the test's independent expected
# answers and is the oracle the eval checks against.
SUITE_UNITS = {
    "distance earth to moon": "km",
    "boiling point of water": "C",
    "speed of light": "m/s",
    "freezing point of water": "C",
    "number of continents": "",
    "days in a week": "",
}


@opik.track(entrypoint=True, name="known-failing-agent")
def run(question: str) -> str:
    value = lookup(question)
    units = SUITE_UNITS.get(question.strip().lower(), "")
    return format_answer(SYSTEM_PROMPT, value, units)


if __name__ == "__main__":
    print(run("boiling point of water"))
    opik.flush_tracker()
