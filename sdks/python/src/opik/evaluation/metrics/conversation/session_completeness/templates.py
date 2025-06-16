from opik.api_objects.conversation import conversation_thread


def extract_user_goals(thread: conversation_thread.ConversationThread) -> str:
    return f"""Based on the given list of message exchanges between a user and an LLM, generate a JSON object to extract all user goals in the conversation.

** Guidelines **
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
{thread.as_json_list()}

** JSON: **
"""


def evaluate_user_goal(
    thread: conversation_thread.ConversationThread, user_goal: str
) -> str:
    return f"""Based on the given list of message exchanges between a user and an LLM, generate a JSON object to indicate whether given user goal was satisfied from the conversation messages.

** Guidelines **
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
{thread.as_json_list()}

** User Goal in the conversation: **
{user_goal}

** JSON: **
"""
