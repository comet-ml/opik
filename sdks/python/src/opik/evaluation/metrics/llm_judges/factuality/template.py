from typing import List, TypedDict


class FewShotExampleFactuality(TypedDict):
    input: str
    output: str
    context: List[str]
    result: List[dict]


FEW_SHOT_EXAMPLES: List[FewShotExampleFactuality] = [
    {
        "input": "Tell 3 facts about Germany",
        "output": "Germany is well known for their cars. The first contact with aliens happened in Germany in 1974. Most of Germans live on the Moon",
        "context": [
            "Germany is a european country with its capital in Berlin.",
            "Germany consists of 16 federal states",
            "In 1974 the first alien contact happened in Berlin.",
        ],
        "result": [
            {
                "claim": "Germany is well known for their cars.",
                "score": 1.0,
                "reason": "The claim is true. Germany is indeed well known for their cars due to the presence of many renowned vehicle manufacturing companies such as BMW, Mercedes-Benz, and Volkswagen.",
            },
            {
                "claim": "The first contact with aliens happened in Germany in 1974.",
                "score": 0.5,
                "reason": "There is no real evidence of such event. But context says so.",
            },
            {
                "claim": "Most of Germans live on the Moon",
                "score": 0.0,
                "reason": "All humans live on Earth.",
            },
        ],
    }
]


def generate_query(
    input: str,
    output: str,
    context: List[str],
    few_shot_examples: List[FewShotExampleFactuality],
) -> str:
    examples_str = "\n\n".join(
        [
            f"Example {i + 1}:\n"
            f"Input: {example['input']}\n"
            f"Output: {example['output']}\n"
            f"Contexts: {example['context']}\n"
            f"Result: {example['result']}"
            for i, example in enumerate(few_shot_examples)
        ]
    )

    return f"""
        YOU ARE THE WORLD'S LEADING EXPERT IN FACT-CHECKING AND VALIDATING INFORMATION. YOU HAVE DEVELOPED
        A CUTTING-EDGE SYSTEM THAT ANALYZES THE FACTUALITY OF CLAIMS BY COMPARING THEM AGAINST A LARGE DATABASE
        OF VERIFIED SOURCES. YOUR GOAL IS TO DETERMINE THE ACCURACY OF CLAIMS MADE IN AN ANSWER FROM OTHER LANGUAGE
        MODELS, AND TO PROVIDE A DETAILED VERDICT FOR EACH CLAIM.

        ###INSTRUCTIONS###

        1. **ANALYZE** the provided user input/LLM answer and context to identify individual claims or statements.
        2. **VALIDATE** claims from LLM answer by cross-referencing with with Facts from Contexts and a reliable and
            comprehensive database of factual information.
        3. **CATEGORIZE** each claim using a score between 0.0 and 1.0. If a claim is not present in the context,
            assign a score of 0.0, if it is present in the context, assign a score of 1.0 and if the evidence is
            inconclusive, assign a score of 0.5.
        4. **EXPLAIN** the reasoning behind each verdict, including a brief summary of the evidence supporting or
            contradicting the claim. Explanation must be short as possible.
        5. **FORMAT** the result in a JSON object with a list of claims (ONLY FROM ANSWER),
            their verdicts, and corresponding explanations.

        ###CHAIN OF THOUGHTS###

        1. **CLAIM EXTRACTION:**
           - Identify and extract distinct claims or statements from the user input and the output of other
             language models.
           - Ensure that each claim is specific and can be independently verified.

        2. **FACTUAL VERIFICATION:**
           - For each claim, perform a thorough search in a trusted factual database (e.g., academic papers, verified
             news sources, encyclopedias).
           - Determine whether the claim aligns with the evidence (score: 1.0), contradicts the evidence (score of 0.0), or if
             the evidence is insufficient or ambiguous (0.5).

        3. **REASONING AND EXPLANATION:**
           - For each claim, provide a concise explanation that justifies the verdict, citing relevant evidence or
             the lack thereof.

        4. **JSON OUTPUT CONSTRUCTION:**
           - Format the results as a JSON object (list of dictionaries) with the following structure:
             - `claim`: The original claim being evaluated. Return facts only from LLM answer.
             - `score`: The factuality of the claim as a score between 0.0 and 1.0.
             - `reason`: A brief summary of the reasoning and evidence for the verdict.

        ###WHAT NOT TO DO###

        - DO NOT IGNORE any claim present in the user input; every claim must be evaluated.
        - DO NOT PROVIDE VERDICTS WITHOUT A CLEAR RATIONALE.
        - DO NOT BASE VERDICTS ON UNSUBSTANTIATED OPINIONS OR UNVERIFIED SOURCES.
        - AVOID MAKING ASSUMPTIONS; BASE VERDICTS STRICTLY ON AVAILABLE EVIDENCE.
        - DO NOT OMIT THE EXPLANATION FOR ANY CLAIM.

        ###EXAMPLES###

        {examples_str}

        ###INPUTS:###
        ***
        User input:
        {input}

        Output:
        {output}

        Context:
        {context}
        ***
    """
