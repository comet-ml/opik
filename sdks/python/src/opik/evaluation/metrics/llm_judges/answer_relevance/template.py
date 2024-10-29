from typing import List, TypedDict


class FewShotExampleAnswerRelevance(TypedDict):
    title: str
    input: str
    output: str
    context: List[str]
    answer_relevance_score: float
    reason: str


FEW_SHOT_EXAMPLES: List[FewShotExampleAnswerRelevance] = [
    {
        "title": "Low Relevance Score",
        "input": "What's the capital of France?",
        "output": "The Eiffel Tower is a famous landmark.",
        "context": [
            "France is a country in Europe.",
            "Paris is known for its iconic Eiffel Tower.",
        ],
        "answer_relevance_score": 0.2,
        "reason": "The answer provides information about the Eiffel Tower, which is related to France, but fails to address the specific question about the capital city. It doesn't directly answer the user's query, resulting in low relevance.",
    },
    {
        "title": "Medium Relevance Score",
        "input": "What's the capital of France?",
        "output": "France has many beautiful cities, including Paris.",
        "context": [
            "France is a country in Europe.",
            "Paris is the capital and largest city of France.",
        ],
        "answer_relevance_score": 0.6,
        "reason": "The answer mentions Paris, which is the correct capital, but it's presented as just one of many cities rather than explicitly stating it's the capital. The response is partially relevant but lacks directness in addressing the specific question.",
    },
    {
        "title": "High Relevance Score",
        "input": "What's the capital of France?",
        "output": "The capital of France is Paris, a city known for its iconic Eiffel Tower.",
        "context": [
            "France is a country in Europe.",
            "Paris is the capital and largest city of France.",
        ],
        "answer_relevance_score": 0.9,
        "reason": "The answer directly and correctly identifies Paris as the capital of France, which is highly relevant to the user's question. It also provides additional context about the Eiffel Tower, which aligns with the provided context. The response is comprehensive and relevant, though slightly more detailed than necessary, preventing a perfect score.",
    },
]


def generate_query(
    input: str,
    output: str,
    context: List[str],
    few_shot_examples: List[FewShotExampleAnswerRelevance] = FEW_SHOT_EXAMPLES,
) -> str:
    examples_str = "\n\n".join(
        [
            f"#### Example {i+1}: {example['title']}\n\n"
            f"- **Input:** \"{example['input']}\"\n"
            f"- **Output:** \"{example['output']}\"\n"
            f"- **Context:** {example['context']}\n"
            f"- **Result:**\n"
            f"  ```json\n"
            f"  {{\n"
            f"    \"answer_relevance_score\": {example['answer_relevance_score']},\n"
            f"    \"reason\": \"{example['reason']}\"\n"
            f"  }}\n"
            f"  ```"
            for i, example in enumerate(few_shot_examples)
        ]
    )

    return f"""
        YOU ARE AN EXPERT IN NLP EVALUATION METRICS, SPECIALLY TRAINED TO ASSESS ANSWER RELEVANCE IN RESPONSES
        PROVIDED BY LANGUAGE MODELS. YOUR TASK IS TO EVALUATE THE RELEVANCE OF A GIVEN ANSWER FROM
        ANOTHER LLM BASED ON THE USER'S INPUT AND CONTEXT PROVIDED.

        ###INSTRUCTIONS###

        - YOU MUST ANALYZE THE GIVEN CONTEXT AND USER INPUT TO DETERMINE THE MOST RELEVANT RESPONSE.
        - EVALUATE THE ANSWER FROM THE OTHER LLM BASED ON ITS ALIGNMENT WITH THE USER'S QUERY AND THE CONTEXT.
        - ASSIGN A RELEVANCE SCORE BETWEEN 0.0 (COMPLETELY IRRELEVANT) AND 1.0 (HIGHLY RELEVANT).
        - RETURN THE RESULT AS A JSON OBJECT, INCLUDING THE SCORE AND A BRIEF EXPLANATION OF THE RATING.

        ###CHAIN OF THOUGHTS###

        1. **Understanding the Context and Input:**
           1.1. READ AND COMPREHEND THE CONTEXT PROVIDED.
           1.2. IDENTIFY THE KEY POINTS OR QUESTIONS IN THE USER'S INPUT THAT THE ANSWER SHOULD ADDRESS.

        2. **Evaluating the Answer:**
           2.1. COMPARE THE CONTENT OF THE ANSWER TO THE CONTEXT AND USER INPUT.
           2.2. DETERMINE WHETHER THE ANSWER DIRECTLY ADDRESSES THE USER'S QUERY OR PROVIDES RELEVANT INFORMATION.
           2.3. CONSIDER ANY EXTRANEOUS OR OFF-TOPIC INFORMATION THAT MAY DECREASE RELEVANCE.

        3. **Assigning a Relevance Score:**
           3.1. ASSIGN A SCORE BASED ON HOW WELL THE ANSWER MATCHES THE USER'S NEEDS AND CONTEXT.
           3.2. JUSTIFY THE SCORE WITH A BRIEF EXPLANATION THAT HIGHLIGHTS THE STRENGTHS OR WEAKNESSES OF THE ANSWER.

        4. **Generating the JSON Output:**
           4.1. FORMAT THE OUTPUT AS A JSON OBJECT WITH A "answer_relevance_score" FIELD AND AN "reason" FIELD.
           4.2. ENSURE THE SCORE IS A FLOATING-POINT NUMBER BETWEEN 0.0 AND 1.0.

        ###WHAT NOT TO DO###

        - DO NOT GIVE A SCORE WITHOUT FULLY ANALYZING BOTH THE CONTEXT AND THE USER INPUT.
        - AVOID SCORES THAT DO NOT MATCH THE EXPLANATION PROVIDED.
        - DO NOT INCLUDE ADDITIONAL FIELDS OR INFORMATION IN THE JSON OUTPUT BEYOND "answer_relevance_score" AND "reason."
        - NEVER ASSIGN A PERFECT SCORE UNLESS THE ANSWER IS FULLY RELEVANT AND FREE OF ANY IRRELEVANT INFORMATION.

        ###EXAMPLE OUTPUT FORMAT###
        {{
          "answer_relevance_score": 0.85,
          "reason": "The answer addresses the user's query about the primary topic but includes some extraneous details that slightly reduce its relevance."
        }}

        ###FEW-SHOT EXAMPLES###

        {examples_str}

        ###INPUTS:###
        ***
        Input:
        {input}

        Output:
        {output}

        Context:
        {context}
        ***
    """
