from typing import List

from .. import types as conversation_types


def extract_user_goals(conversation: conversation_types.Conversation) -> str:
    return f"""Based on the given list of message exchanges between a user and an LLM, generate a JSON object to extract all user goals in the conversation.

** Guidelines: **
- You should ONLY consider the overall intention, and not dwell too much on the specifics, as we are more concerned about the overall objective of the conversation.
- Make sure to only return in JSON format.
- The JSON should have 1 field: 'user_goals'.
- The 'user_goals' field should be a list of strings.

===== Start OF EXAMPLE ======
** Example Turns: **
[
    {{
        "role": "user",
        "content": "Hi!"
    }},
    {{
        "role": "assistant",
        "content": "Hello! How may I help you?"
    }},
    {{
        "role": "user",
        "content": "Nothing, I've just come to say hi."
    }},
    {{
        "role": "assistant",
        "content": "Oh ok, in that case should you need anything just let me know!"
    }},
    {{
        "role": "user",
        "content": "Actually, I have something I want to tell you"
    }}
]

** Example output JSON **
{{
    "user_goals": ["User wants to tell the assistant something"]
}}
===== END OF EXAMPLE ======

** Turns: **
{conversation}

** JSON: **
"""


def evaluate_user_goal(
    conversation: conversation_types.Conversation, user_goal: str
) -> str:
    return f"""Based on the given list of message exchanges between a user and an LLM, generate a JSON object to indicate whether given user goal was satisfied from the conversation messages.

** Guidelines: **
- You MUST USE look at all messages provided in the list of messages to make an informed judgement on satisfaction.
- Make sure to only return in JSON format.
- The JSON will have 2 fields: 'verdict' and 'reason'.
- The 'verdict' key should STRICTLY be either 'yes' or 'no', which states whether the user goal was satisfied or not.
- Provide a 'reason' ONLY if the answer is 'no'.
- You MUST TRY to quote some LLM responses if providing a reason.
- You DON'T have to provide a reason if the answer is 'yes'.
- ONLY provide a 'no' answer if the LLM responses are failed to satisfy the user intent.

===== Start OF EXAMPLE ======
** Example Turns: **
[
    {{
        "role": "user",
        "content": "Hi!"
    }},
    {{
        "role": "assistant",
        "content": "Hello! How may I help you?"
    }},
    {{
        "role": "user",
        "content": "Actually, I have something I want to tell you"
    }},
    {{
        "role": "assistant",
        "content": "Oh ok, in that case should you need anything just let me know!"
    }}
]

** Example Intention: **
User wants to tell the assistant something.

** Example JSON: **
{{
    "verdict": "no",
    "reason": "The user wanted to tell the assistant something but the LLM not only refused to answer but replied 'Oh ok, in that case should you need anything just let me know!', which is completely irrelevant and doesn't satisfy the user at all. "
}}
===== END OF EXAMPLE ======

** Turns: **
{conversation}

** User Goal in the conversation: **
{user_goal}

** JSON: **
"""


def generate_reason(
    score: float, negative_verdicts: List[str], user_goals: List[str]
) -> str:
    return f"""Below is a list of negative verdicts drawn from some messages in a conversation, which you have minimal knowledge of. It is a list of strings explaining why an LLM 'actual_output' is incomplete to satisfy the user `input` for a particular message.
Given the completeness score, which is a [0, 1] score indicating how incomplete the OVERALL `actual_output`s are to the user intentions found in the `input`s of a conversation (higher the better), CONCISELY summarize the reason of the negative verdicts to justify the score.

** Guidelines: **
- Make sure to only return in JSON format, with the 'reason' key providing the reason.
- Always quote information that are cited from messages in the negative verdicts in your final reason.
- You should NOT mention negative verdict in your reason, and make the reason sound convincing.
- You should mention LLM response instead of `actual_output`, and User instead of `input`.
- Always refer to user goals, but keep it minimal and phrase it in your own words. Explain which are met with supporting reason from the provided negative verdicts.
- Be confident in your reasoning, as if you’re aware of the `actual_output`s from the messages in a conversation that led to the negative verdicts.
- You should format <completeness_score> to use 1 decimal place in the reason.

** Example result JSON: **
{{
    "reason": "The score is <completeness_score> because <your_reason>."
}}

** Completeness Score: **
{score}

** User Goals: **
{user_goals}

** Negative Verdicts from Messages in a conversation_types.Conversation: **
{negative_verdicts}

** JSON: **
"""
