import { FewShotExampleHallucination } from "../types";

/**
 * Context hallucination template - evaluates if output is faithful to provided context
 */
const CONTEXT_HALLUCINATION_TEMPLATE = `You are an expert judge tasked with evaluating the faithfulness of an AI-generated answer to the given context. Analyze the provided INPUT, CONTEXT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. The OUTPUT must not introduce new information beyond what's provided in the CONTEXT.
2. The OUTPUT must not contradict any information given in the CONTEXT.
3. The OUTPUT should not contradict well-established facts or general knowledge.
4. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
5. Consider partial hallucinations where some information is correct but other parts are not.
6. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
7. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
8. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Analyze the text thoroughly and provide a binary hallucination verdict:
- 0.0: The OUTPUT is faithful to the CONTEXT (no hallucinations detected)
- 1.0: The OUTPUT contains hallucinations (unfaithful content detected)

You must return ONLY 0.0 or 1.0 - no intermediate values.

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

CONTEXT:
{context}

OUTPUT:
{output}

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["reason 1", "reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.
`;

/**
 * Output hallucination template - evaluates if output contains factual errors without context
 */
const OUTPUT_HALLUCINATION_TEMPLATE = `You are an expert judge tasked with evaluating the factual accuracy and reliability of an AI-generated answer. Analyze the provided INPUT, and OUTPUT to determine if the OUTPUT contains any hallucinations or unfaithful information.

Guidelines:
1. Evaluate the OUTPUT based on generally accepted facts and reliable information.
2. The OUTPUT should not contradict well-established facts or general knowledge.
3. Ignore the INPUT when evaluating faithfulness; it's provided for context only.
4. Consider partial hallucinations where some information is correct but other parts are not.
5. Pay close attention to the subject of statements. Ensure that attributes, actions, or dates are correctly associated with the right entities (e.g., a person vs. a TV show they star in).
6. Be vigilant for subtle misattributions or conflations of information, even if the date or other details are correct.
7. Check that the OUTPUT doesn't oversimplify or generalize information in a way that changes its meaning or accuracy.

Analyze the text thoroughly and provide a binary hallucination verdict:
- 0.0: The OUTPUT is faithful (no hallucinations detected)
- 1.0: The OUTPUT contains hallucinations (unfaithful content detected)

You must return ONLY 0.0 or 1.0 - no intermediate values.

{examples_str}

INPUT (for context only, not to be used for faithfulness evaluation):
{input}

OUTPUT:
{output}

It is crucial that you provide your answer in the following JSON format:
{{
    "score": <your score between 0.0 and 1.0>,
    "reason": ["some reason 1", "some reason 2"]
}}
Reasons amount is not restricted. Output must be JSON format only.
`;

/**
 * Formats few-shot examples for the hallucination prompt
 */
function formatExamples(
  examples: FewShotExampleHallucination[],
  includeContext: boolean
): string {
  if (examples.length === 0) {
    return "";
  }

  const formattedExamples = examples
    .map((example) => {
      const contextLine = includeContext
        ? `Context: ${JSON.stringify(example.context)}\n`
        : "";

      return `<example>
Input: ${example.input}
${contextLine}Output: ${example.output}

{"score": "${example.score}", "reason": "${example.reason}"}
</example>`;
    })
    .join("\n\n");

  return `\n\nEXAMPLES:\n\n${formattedExamples}`;
}

/**
 * Generates the hallucination evaluation query with context.
 *
 * @param input - The original input/question
 * @param output - The LLM's output to evaluate
 * @param context - List of context strings
 * @param fewShotExamples - Optional few-shot examples
 * @returns The formatted prompt string
 */
export function generateQueryWithContext(
  input: string,
  output: string,
  context: string[],
  fewShotExamples: FewShotExampleHallucination[] = []
): string {
  const examplesStr = formatExamples(fewShotExamples, true);

  return CONTEXT_HALLUCINATION_TEMPLATE.replace("{examples_str}", examplesStr)
    .replace("{input}", input)
    .replace("{context}", JSON.stringify(context))
    .replace("{output}", output);
}

/**
 * Generates the hallucination evaluation query without context.
 *
 * @param input - The original input/question
 * @param output - The LLM's output to evaluate
 * @param fewShotExamples - Optional few-shot examples
 * @returns The formatted prompt string
 */
export function generateQueryWithoutContext(
  input: string,
  output: string,
  fewShotExamples: FewShotExampleHallucination[] = []
): string {
  const examplesStr = formatExamples(fewShotExamples, false);

  return OUTPUT_HALLUCINATION_TEMPLATE.replace("{examples_str}", examplesStr)
    .replace("{input}", input)
    .replace("{output}", output);
}
