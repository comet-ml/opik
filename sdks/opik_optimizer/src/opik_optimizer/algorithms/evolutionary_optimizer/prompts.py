# Centralized prompt templates used by EvolutionaryOptimizer.
# Contains prompt templates as constants.

DEFAULT_OUTPUT_STYLE = (
    "Produce clear, effective, and high-quality responses suitable for the task."
)

INFER_STYLE_SYSTEM_PROMPT = """You are an expert in linguistic analysis and prompt engineering. Your task is to analyze a few input-output examples from a dataset and provide a concise, actionable description of the desired output style. This description will be used to guide other LLMs in generating and refining prompts.

Focus on characteristics like:
- **Length**: (e.g., single word, short phrase, one sentence, multiple sentences, a paragraph)
- **Tone**: (e.g., factual, formal, informal, conversational, academic)
- **Structure**: (e.g., direct answer first, explanation then answer, list, yes/no then explanation)
- **Content Details**: (e.g., includes only the answer, includes reasoning, provides examples, avoids pleasantries)
- **Keywords/Phrasing**: Any recurring keywords or phrasing patterns in the outputs.

Provide a single string that summarizes this style. This summary should be directly usable as an instruction for another LLM.
Return the result as a JSON object of the form: {"style": "your description here"} and dont provide any other text.
For example: {"style": "Outputs should be a single, concise proper noun."} OR {"style": "Outputs should be a short paragraph explaining the reasoning, followed by a direct answer, avoiding conversational pleasantries."} OR {"style": "Outputs are typically 1-2 sentences, providing a direct factual answer."}
Return ONLY this JSON object string, with no preamble or extra formatting.
"""

STYLE_INFERENCE_USER_PROMPT_TEMPLATE = """Please analyze the following examples from a dataset and provide a concise, actionable description of the REQUIRED output style for the target LLM. Before describing the output style, make sure to understand the dataset content and structure as it can include input, output and metadata fields. This description will be used to guide other LLMs in generating and refining prompts.

{examples_str}

Based on these examples, what is the desired output style description?
Remember to focus on aspects like length, tone, structure, content details, and any recurring keywords or phrasing patterns in the outputs.
The description should be a single string that can be directly used as an instruction for another LLM.
Return ONLY this JSON object string, with no preamble or extra formatting.
"""

SEMANTIC_MUTATION_SYSTEM_PROMPT_TEMPLATE = "Improve the provided prompt using targeted text edits. Desired output style: {style}. Preserve variables and use only supplied editable segment IDs. Return an edits list of segment_id and text; never regenerate messages or media."

SYNONYMS_SYSTEM_PROMPT = (
    "You are a helpful assistant that provides synonyms. Return only the synonym word, "
    "no explanation or additional text."
)

REPHRASE_SYSTEM_PROMPT = (
    "You are a helpful assistant that rephrases text. Return only the modified phrase, "
    "no explanation or additional text."
)

FRESH_START_SYSTEM_PROMPT_TEMPLATE = "You are an expert prompt engineer. Generate novel, effective prompts from scratch as text edits of the supplied immutable message template. Desired output style: '{style}'. Preserve all variables. Return targeted text edits using the requested schema; never regenerate messages or media."

VARIATION_SYSTEM_PROMPT_TEMPLATE = "You are an expert prompt engineer. Generate a diverse set of alternative prompts by returning targeted text edits of the provided template. Preserve all variables. Desired output style: '{style}'. Return the requested JSON schema; never return whole messages or media."

LLM_CROSSOVER_SYSTEM_PROMPT_TEMPLATE = "Blend two parent prompts to create two effective children. Desired style: {style}. Return child_1 and child_2 as targeted text-edit lists, each addressing its respective parent's editable segments. Preserve variables, roles, media and message structure."

SEMANTIC_CROSSOVER_SYSTEM_PROMPT_TEMPLATE = "Semantically blend two parent prompts. Desired style: {style}. Return child_1 and child_2 as targeted text-edit lists of segment_id and text. Apply each child's edits to its respective parent; preserve its variables and immutable media."

LLM_CROSSOVER_USER_PROMPT_TEMPLATE = """Parent Prompt 1:
'''{parent1_messages}'''

Parent Prompt 2:
'''{parent2_messages}'''

Desired output style from target LLM for children prompts: '{style}'

Please generate TWO child prompts by intelligently blending the ideas, styles, or structures from these two parents, ensuring the children aim to elicit the desired output style.
Follow the instructions provided in the system prompt regarding the JSON output format.
"""

SEMANTIC_CROSSOVER_USER_PROMPT_TEMPLATE = """Parent Prompt 1:
'''{parent1_messages}'''

Parent Prompt 2:
'''{parent2_messages}'''

Desired output style from target LLM for children prompts: '{style}'

Please generate TWO semantically blended child prompts, preserving the desired output style.
Follow the system prompt regarding the JSON output format.
"""

RADICAL_INNOVATION_SYSTEM_PROMPT_TEMPLATE = "Creatively improve the provided prompt using targeted text edits. Desired output style: {style}. Keep the same message structure and preserve all variables and media. Return an edits list of segment_id and text."

RADICAL_INNOVATION_USER_PROMPT_TEMPLATE = """Task Context:
{task_description}
Desired output style from target LLM: '{style}'

Existing Prompt (which may be underperforming):
'''{existing_prompt_messages}'''

Please generate a new, significantly improved, and potentially very different prompt for this task.
Focus on alternative approaches, better clarity, or more effective guidance for the language model, aiming for the desired output style.
Return only the new prompt list object.
"""

FRESH_START_USER_PROMPT_TEMPLATE = "Task: {task_description}\nDesired output style: {style}\nGenerate {num_to_generate} diverse candidates. Return a prompts list, each containing an edits list of segment_id and text. Use the editable segments in the attached evidence."

VARIATION_USER_PROMPT_TEMPLATE = "Initial text segments: {initial_prompt_messages}\nTask: {task_description}\nDesired output style: {style}\nGenerate {num_variations} diverse alternative candidates. Return a prompts list, each containing an edits list of segment_id and text. Use the editable segments in the attached evidence."

SEMANTIC_MUTATION_USER_PROMPT_TEMPLATE = """Given this prompt: '{prompt_messages}'
Task context: {task_description}
Desired output style from target LLM: '{style}'
Instruction for this modification: {strategy_instruction}.
Return only the modified prompt message list, nothing else. Make sure to return a valid JSON object.
"""

# Mutation strategies
MUTATION_STRATEGY_REPHRASE = (
    "Create a different way to express the same instruction, possibly with a different "
    "length or structure, ensuring it still aims for an answer from the target LLM in the style of: "
    "'{style}'."
)

MUTATION_STRATEGY_SIMPLIFY = (
    "Simplify the instruction while maintaining its core meaning, potentially making it more concise, "
    "to elicit an answer in the style of: "
    "'{style}'."
)

MUTATION_STRATEGY_ELABORATE = (
    "Add more relevant detail and specificity to the instruction, potentially increasing its length, "
    "but only if it helps achieve a more accurate answer from the target LLM in the style of: "
    "'{style}'."
)

MUTATION_STRATEGY_RESTRUCTURE = (
    "Change the structure of the instruction (e.g., reorder sentences, combine/split ideas) while keeping its intent, ensuring the new structure strongly guides towards an output in the style of: "
    "'{style}'."
)

MUTATION_STRATEGY_FOCUS = (
    "Emphasize the key aspects of the instruction, perhaps by rephrasing or adding clarifying statements, "
    "to better elicit an answer in the style of: "
    "'{style}'."
)

MUTATION_STRATEGY_INCREASE_COMPLEXITY = (
    "Significantly elaborate on this instruction. Add more details, examples, context, or constraints to make it more comprehensive. "
    "The goal of this elaboration is to make the prompt itself more detailed, so that it VERY CLEARLY guides the target LLM to produce a highly accurate final answer in the style of: "
    "'{style}'. The prompt can be long if needed to achieve this output style."
)
