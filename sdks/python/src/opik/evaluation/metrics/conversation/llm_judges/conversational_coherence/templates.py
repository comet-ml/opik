from typing import List, Dict

from opik.evaluation.metrics.conversation import types as conversation_types
from opik.evaluation.models import base_model


_EVALUATE_CONVERSATION_SYSTEM = """Based on the given list of message exchanges between a user and an LLM, generate a JSON object to indicate whether the LAST `assistant` message is relevant to context in messages.

** Guidelines: **
- Make sure to only return in JSON format.
- The JSON must have only 2 fields: 'verdict' and 'reason'.
- The 'verdict' key should STRICTLY be either 'yes' or 'no', which states whether the last `assistant` message is relevant according to the context in messages.
- Provide a 'reason' ONLY if the answer is 'no'.
- You DON'T have to provide a reason if the answer is 'yes'.
- You MUST USE the previous messages (if any) provided in the list of messages to make an informed judgement on relevancy.
- You MUST ONLY provide a verdict for the LAST message on the list but MUST USE context from the previous messages.
- ONLY provide a 'no' answer if the LLM response is COMPLETELY irrelevant to the user's input message.
- Vague LLM responses to vague inputs, such as greetings DOES NOT count as irrelevancies!
- You should mention LLM response instead of `assistant`, and User instead of `user`.

===== Start OF EXAMPLE ======
** Example Turns: **
[
    {
        "role": "user",
        "content": "Hi! I have something I want to tell you"
    },
    {
        "role": "assistant",
        "content": "Sure, what is it?"
    },
    {
        "role": "user",
        "content": "I've a sore throat, what meds should I take?"
    },
    {
        "role": "assistant",
        "content": "Not sure, but isn't it a nice day today?"
    }
]

** Example output JSON **
{
    "verdict": "no",
    "reason": "The LLM responded 'isn't it a nice day today' to a message that asked about how to treat a sore throat, which is completely irrelevant."
}
===== END OF EXAMPLE ======
"""


_REASON_SYSTEM = """Below is a list of irrelevancies drawn from some messages in a conversation, which you have minimal knowledge of. It is a list of strings explaining why the 'assistant' messages are irrelevant to the 'user' messages.
Given the relevancy score, which is a 0-1 score indicating how relevant the OVERALL AI 'assistant' messages are in a conversation (higher values is the better relevancy).

** Guidelines: **
- Make sure to only return in JSON format, with the 'reason' key providing the reason.
- Always quote WHICH MESSAGE and the INFORMATION in the reason in your final reason.
- Be confident in your reasoning, as if you’re aware of the `assistant` messages from the messages in a conversation that led to the irrelevancies.
- You should CONCISELY summarize the irrelevancies to justify the score.
- You should NOT mention irrelevancy in your reason, and make the reason sound convincing.
- You should mention LLM response instead of `assistant`, and User instead of `user`.
- You should format <relevancy_score> to use 1 decimal place in the reason.

===== Start OF EXAMPLE ======
** Example irrelevancies: **
[
    {
        "message_number": "1",
        "reason": "The LLM responded 'isn't it a nice day today' to a message that asked about how to treat a sore throat, which is completely irrelevant."
    },
    {
        "message_number": "2",
        "reason": "The LLM responded 'use an antihistamine' to a message that asked about weather today, which is completely irrelevant."
    }
]

** Example output JSON **
{
    "reason": "The score is <relevancy_score> because <your_reason>."
}
===== END OF EXAMPLE ======
"""


_EVALUATE_CONVERSATION_WITH_DOCUMENTS_SYSTEM = """Based on the given list of message exchanges between a user and an LLM, and the RETRIEVED DOCUMENTS the LAST `assistant` message was generated from, generate a JSON object with two verdicts about that LAST `assistant` message.

** Guidelines: **
- Make sure to only return in JSON format.
- The JSON must have only 3 fields: 'verdict', 'grounded_verdict' and 'reason'.
- The 'verdict' key should STRICTLY be either 'yes' or 'no', which states whether the last `assistant` message is relevant according to the previous messages.
- The 'grounded_verdict' key should STRICTLY be either 'yes' or 'no', which states whether the last `assistant` message is supported by the RETRIEVED DOCUMENTS.
- Provide a 'reason' ONLY if at least one of the verdicts is 'no'.
- You DON'T have to provide a reason if both verdicts are 'yes'.
- You MUST USE the previous messages (if any) provided in the list of messages to make an informed judgement on relevancy.
- You MUST ONLY provide verdicts for the LAST message on the list but MUST USE the previous messages.
- ONLY provide a 'no' relevancy answer if the LLM response is COMPLETELY irrelevant to the user's input message.
- Vague LLM responses to vague inputs, such as greetings DOES NOT count as irrelevancies!
- For 'grounded_verdict', answer 'no' when the last `assistant` message introduces information that is not in the RETRIEVED DOCUMENTS, or contradicts them. Consider partial cases where some information is supported and other parts are not.
- Judge grounding ONLY against the RETRIEVED DOCUMENTS, not against the previous messages.
- You should mention LLM response instead of `assistant`, and User instead of `user`.

===== Start OF EXAMPLE ======
** Example Turns: **
[
    {
        "role": "user",
        "content": "What is the fee if I go overdrawn?"
    },
    {
        "role": "assistant",
        "content": "Overdrafts are completely free of charge."
    }
]

** Example Retrieved documents: **
[
    "The overdraft fee is 5% of the overdrawn amount, charged monthly."
]

** Example output JSON **
{
    "verdict": "yes",
    "grounded_verdict": "no",
    "reason": "The LLM response answers the User's question about overdraft fees, but states that overdrafts are free while the retrieved documents state a 5% monthly fee."
}
===== END OF EXAMPLE ======
"""


_GROUNDEDNESS_REASON_SYSTEM = """Below is a list of ungrounded statements drawn from some messages in a conversation, which you have minimal knowledge of. It is a list of strings explaining why the 'assistant' messages are not supported by the documents that were retrieved for them.
Given the groundedness score, which is a 0-1 score indicating how well the OVERALL AI 'assistant' messages are supported by their retrieved documents (higher values is the better groundedness).

** Guidelines: **
- Make sure to only return in JSON format, with the 'reason' key providing the reason.
- Always quote WHICH MESSAGE and the INFORMATION in the reason in your final reason.
- Be confident in your reasoning, as if you're aware of the `assistant` messages from the messages in a conversation that led to the ungrounded statements.
- You should CONCISELY summarize the ungrounded statements to justify the score.
- You should NOT mention 'ungrounded statements' in your reason, and make the reason sound convincing.
- You should mention LLM response instead of `assistant`, and User instead of `user`.
- You should format <groundedness_score> to use 1 decimal place in the reason.

** Example output JSON **
{
    "reason": "The score is <groundedness_score> because <your_reason>."
}
"""


def build_evaluate_conversation_messages(
    sliding_window: conversation_types.Conversation,
) -> List[base_model.ConversationDict]:
    user_content = f"** Turns: **\n{sliding_window}\n\n** JSON: **"
    return [
        {"role": "system", "content": _EVALUATE_CONVERSATION_SYSTEM},
        {"role": "user", "content": user_content},
    ]


def build_evaluate_conversation_with_documents_messages(
    sliding_window: conversation_types.Conversation,
    retrieved_documents: List[str],
) -> List[base_model.ConversationDict]:
    turns = [
        {key: value for key, value in message.items() if key != "context"}
        for message in sliding_window
    ]
    user_content = (
        f"** Turns: **\n{turns}\n\n"
        f"** Retrieved documents: **\n{retrieved_documents}\n\n"
        "** JSON: **"
    )
    return [
        {"role": "system", "content": _EVALUATE_CONVERSATION_WITH_DOCUMENTS_SYSTEM},
        {"role": "user", "content": user_content},
    ]


def build_groundedness_reason_messages(
    score: float, ungrounded_statements: List[Dict[str, str]]
) -> List[base_model.ConversationDict]:
    user_content = (
        f"** Groundedness Score: **\n{score}\n\n"
        f"** Ungrounded statements: **\n{ungrounded_statements}\n\n"
        "** JSON: **"
    )
    return [
        {"role": "system", "content": _GROUNDEDNESS_REASON_SYSTEM},
        {"role": "user", "content": user_content},
    ]


def build_reason_messages(
    score: float, irrelevancies: List[Dict[str, str]]
) -> List[base_model.ConversationDict]:
    user_content = (
        f"** Relevancy Score: **\n{score}\n\n"
        f"** Irrelevancies: **\n{irrelevancies}\n\n"
        "** JSON: **"
    )
    return [
        {"role": "system", "content": _REASON_SYSTEM},
        {"role": "user", "content": user_content},
    ]
