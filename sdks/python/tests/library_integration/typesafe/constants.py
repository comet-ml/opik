from typesafe_sdk import Choice, Noul, Score

from ...testlib import ANY_BUT_NONE

MODEL = "jev-latest"

STATE = {"document": "I was charged twice. Please fix this ASAP."}
QUESTIONS = {
    "category": Choice(
        instructions="What is this ticket about?",
        criteria={"billing": None, "technical": None, "other": None},
    ),
    "is_urgent": Noul(instructions="The message conveys urgency"),
    "frustration": Score(
        instructions="How frustrated is the customer?",
        criteria=["calm", "annoyed", "furious"],
    ),
}
QUESTIONS_AS_DICTS = {
    "category": {
        "type": "choice",
        "instructions": "What is this ticket about?",
        "criteria": {"billing": None, "technical": None, "other": None},
    },
    "is_urgent": {"type": "noul", "instructions": "The message conveys urgency"},
    "frustration": {
        "type": "score",
        "instructions": "How frustrated is the customer?",
        "criteria": ["calm", "annoyed", "furious"],
    },
}
# How Opik's jsonable encoder serializes the question objects above (every
# declared field is present, so `Noul.criteria` is logged as None).
EXPECTED_QUESTIONS_LOGGED = {
    **QUESTIONS_AS_DICTS,
    "is_urgent": {**QUESTIONS_AS_DICTS["is_urgent"], "criteria": None},
}
EXPECTED_INPUT = {"state": STATE, "questions": EXPECTED_QUESTIONS_LOGGED}

EXPECTED_TYPESAFE_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.input_tokens": ANY_BUT_NONE,
    "original_usage.output_tokens": ANY_BUT_NONE,
}
