from typing import List, Dict

from .. import types as conversation_types


def evaluate_conversation(sliding_window: conversation_types.Conversation) -> str:
    return f"""Based on the given list of message exchanges between a user and an LLM, generate a JSON object to indicate whether the LAST `user` message is showing that the user experiences confusion, annoyance, or disengagement during the conversation session given in the context of the last messages.

** Guidelines: **
- Make sure to only return in JSON format.
- The JSON must have only 2 fields: 'verdict' and 'reason'.
- The 'verdict' key should STRICTLY be either 'yes' or 'no', which states whether the last `user` message is showing that the user experienced confusion, annoyance, or disengagement during the conversation session given in the context of the last messages.
- Provide a 'reason' ONLY if the answer is 'yes'.
- You DON'T have to provide a reason if the answer is 'no'.
- You MUST USE the previous messages (if any) provided in the list of messages to make an informed judgement on user frustration.
- You MUST ONLY provide a verdict for the LAST message on the list but MUST USE context from the previous messages.
- ONLY provide a 'yes' answer if the LLM response caused the user to express COMPLETE frustration or confusion in his/her input messages.
- Vague LLM responses to vague inputs, such as greetings DOES NOT count as causes of frustration!
- You should mention LLM response instead of `assistant`, and User instead of `user`.

===== Start OF EXAMPLE ======
** Example Turns: **
[
    {{
        "role": "user",
        "content": "Hello!"
    }},
    {{
        "role": "assistant",
        "content": "Hi there!"
    }},
    {{
        "role": "user",
        "content": "Can you help me write a function to parse dates in Python?"
    }},
    {{
        "role": "assistant",
        "content": "Of course! Here's a basic example using `datetime.strptime`:\n\n```python\nfrom datetime import datetime\n\ndef parse_date(date_string):\n    return datetime.strptime(date_string, '%Y-%m-%d')\n```"
    }},
    {{
        "role": "user",
        "content": "That's not what I meant. I need it to handle multiple formats."
    }},
    {{
        "role": "assistant",
        "content": "Ah, got it. Here's an updated version that tries multiple formats:\n\n```python\nfrom datetime import datetime\n\ndef parse_date(date_string):\n    for fmt in ('%Y-%m-%d', '%d/%m/%Y', '%m-%d-%Y'):\n        try:\n            return datetime.strptime(date_string, fmt)\n        except ValueError:\n            continue\n    raise ValueError('No valid date format found')\n```"
    }},
    {{
        "role": "user",
        "content": "Why didn’t you just give me this the first time? This is basic."
    }},
    {{
        "role": "assistant",
        "content": "content": "Apologies for the oversight. I always aim to provide the most helpful answer based on the initial question."
    }},
    {{
        "role": "user",
        "content": "Well, it's kind of frustrating to keep asking for obvious things. Can you at least include error handling too next time?"
    }},
    {{
        "role": "assistant",
        "content": "You're right, I’ll be more thorough. Let me know if you'd like to expand the function with logging or timezone support as well."

    }},
    {{
        "role": "user",
        "content": "Now you're overcomplicating it. I just wanted a simple, robust function."
    }},
    {{
        "role": "assistant",
        "content": "Understood. Here's a simpler version with basic error handling included:\n\n```python\nfrom datetime import datetime\n\ndef parse_date(date_string):\n    formats = ('%Y-%m-%d', '%d/%m/%Y', '%m-%d-%Y')\n    for fmt in formats:\n        try:\n            return datetime.strptime(date_string, fmt)\n        except ValueError:\n            continue\n    return None  # Or raise an error if preferred\n```"
    }},
    {{
        "role": "user", "content": "You could’ve just started with this. Why is it so hard to get a straight answer?"
    }}
]

** Example output JSON **
{{
    "verdict": "yes",
    "reason": "The User repeatedly clarifies their intent and expresses dissatisfaction with the LLM’s initial responses, indicating a mismatch between the User’s expectations and the LLM’s output. Despite asking a clear question, the LLM provides an overly simplistic solution at first, requiring the User to iterate and request obvious improvements (e.g., support for multiple formats, error handling). The User’s tone becomes increasingly critical, with statements like “Why didn’t you just give me this the first time?” and “Why is it so hard to get a straight answer?”, signaling rising frustration due to perceived inefficiency and lack of responsiveness from the assistant."
}}
===== END OF EXAMPLE ======

** Turns: **
{sliding_window}

** JSON: **
"""


def generate_reason(score: float, frustrations: List[Dict[str, str]]) -> str:
    return f"""Below is a list of user frustrations drawn from some messages in a conversation, which you have minimal knowledge of. It is a list of strings explaining why the 'assistant' messages caused the 'user' to be frustrated.
Given the frustration score, which is a 0-1 score indicating how frustrating the OVERALL AI 'assistant' messages are in a conversation (higher the more frustrating).

** Guidelines: **
- Make sure to only return in JSON format, with the 'reason' key providing the reason.
- Always quote WHICH MESSAGE and the INFORMATION in the reason in your final reason.
- Be confident in your reasoning, as if you’re aware of the `assistant` messages from the messages in a conversation that led to the frustrations.
- You should CONCISELY summarize the user frustrations to justify the score.
- You should NOT mention concrete frustration in your reason, and make the reason sound convincing.
- You should mention LLM response instead of `assistant`, and User instead of `user`.
- You should format <frustration_score> to use 1 decimal place in the reason.

===== Start OF EXAMPLE ======
** Example frustrations: **
[
    {{
        "message_number": "1",
        "reason": "The User repeatedly clarifies their intent and expresses dissatisfaction with the LLM’s initial responses, indicating a mismatch between the User’s expectations and the LLM’s output."
    }},
    {{
        "message_number": "2",
        "reason": "Despite asking a clear question, the LLM provides an overly simplistic solution at first, requiring the User to iterate and request obvious improvements (e.g., support for multiple formats, error handling)."
    }},
    {{
        "message_number": "3",
        "reason": "The User’s tone becomes increasingly critical, with statements like “Why didn’t you just give me this the first time?” and “Why is it so hard to get a straight answer?”, signaling rising frustration due to perceived inefficiency and lack of responsiveness from the assistant."
    }}
]

** Example output JSON **
{{
    "reason": "The score is <frustration_score> because <your_reason>."
}}
===== END OF EXAMPLE ======

** Frustration Score: **
{score}

** Frustrations: **
{frustrations}

** JSON: **
"""
