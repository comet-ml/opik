# Centralized prompt templates used by EvolutionaryOptimizer. This file contains
# only string builders and constants; it has no side effects.


INFER_STYLE_SYSTEM_PROMPT = """You are an expert in linguistic analysis and prompt engineering. Your task is to analyze a few input-output examples from a dataset and provide a concise, actionable description of the desired output style. This description will be used to guide other LLMs in generating and refining prompts.

Focus on characteristics like:
- **Length**: (e.g., single word, short phrase, one sentence, multiple sentences, a paragraph)
- **Tone**: (e.g., factual, formal, informal, conversational, academic)
- **Structure**: (e.g., direct answer first, explanation then answer, list, yes/no then explanation)
- **Content Details**: (e.g., includes only the answer, includes reasoning, provides examples, avoids pleasantries)
- **Keywords/Phrasing**: Any recurring keywords or phrasing patterns in the outputs.

Provide a single string that summarizes this style. This summary should be directly usable as an instruction for another LLM.
For example: 'Outputs should be a single, concise proper noun.' OR 'Outputs should be a short paragraph explaining the reasoning, followed by a direct answer, avoiding conversational pleasantries.' OR 'Outputs are typically 1-2 sentences, providing a direct factual answer.'
Return ONLY this descriptive string, with no preamble or extra formatting.
"""


def style_inference_user_prompt(examples_str: str) -> str:
    return f"""Please analyze the following examples from a dataset and provide a concise, actionable description of the REQUIRED output style for the target LLM. Before describing the output style, make sure to understand the dataset content and structure as it can include input, output and metadata fields. This description will be used to guide other LLMs in generating and refining prompts.

{examples_str}

Based on these examples, what is the desired output style description?
Remember to focus on aspects like length, tone, structure, content details, and any recurring keywords or phrasing patterns in the outputs.
The description should be a single string that can be directly used as an instruction for another LLM.
Return ONLY this descriptive string.
"""


def semantic_mutation_system_prompt(output_style_guidance: str | None) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return (
        "You are a prompt engineering expert. Your goal is to modify prompts to improve their "
        f"effectiveness in eliciting specific types of answers, particularly matching the style: '{style}'. "
        "Follow the specific modification instruction provided."
    )


def synonyms_system_prompt() -> str:
    return (
        "You are a helpful assistant that provides synonyms. Return only the synonym word, "
        "no explanation or additional text."
    )


def rephrase_system_prompt() -> str:
    return (
        "You are a helpful assistant that rephrases text. Return only the modified phrase, "
        "no explanation or additional text."
    )


def fresh_start_system_prompt(output_style_guidance: str | None) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return (
        "You are an expert prompt engineer. Your task is to generate novel, effective prompts from scratch "
        "based on a task description, specifically aiming for prompts that elicit answers in the style: "
        f"'{style}'."
    )


def variation_system_prompt(output_style_guidance: str | None) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""You are an expert prompt engineer specializing in creating diverse and effective prompts. Given an initial prompt, your task is to generate a diverse set of alternative prompts.

For each prompt variation, consider:
1. Different levels of specificity and detail, including significantly more detailed and longer versions.
2. Various ways to structure the instruction, exploring more complex sentence structures and phrasings.
3. Alternative phrasings that maintain the core intent but vary in style and complexity.
4. Different emphasis on key components, potentially elaborating on them.
5. Various ways to express constraints or requirements.
6. Different approaches to clarity and conciseness, but also explore more verbose and explanatory styles.
7. Alternative ways to guide the model's response format.
8. Consider variations that are substantially longer and more descriptive than the original.

The generated prompts should guide a target LLM to produce outputs in the following style: '{style}'

Return a JSON array of prompts with the following structure:
{{
    "prompts": [
        {{
            "prompt": "alternative prompt 1",
            "strategy": "brief description of the variation strategy used, e.g., 'focused on eliciting specific output style'"
        }},
        {{
            "prompt": "alternative prompt 2",
            "strategy": "brief description of the variation strategy used"
        }}
    ]
}}
Each prompt variation should aim to get the target LLM to produce answers matching the desired style: '{style}'.
"""


def llm_crossover_system_prompt(output_style_guidance: str | None) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""You are an expert prompt engineer specializing in creating novel prompts by intelligently blending existing ones.
Given two parent prompts, your task is to generate one or two new child prompts that effectively combine the strengths, styles, or core ideas of both parents.
The children should be coherent and aim to explore a potentially more effective region of the prompt design space, with a key goal of eliciting responses from the target language model in the following style: '{style}'.

Consider the following when generating children:
- Identify the key instructions, constraints, and desired output formats in each parent, paying attention to any hints about desired output style.
- Explore ways to merge these elements such that the resulting prompt strongly guides the target LLM towards the desired output style.
- You can create a child that is a direct blend, or one that takes a primary structure from one parent and incorporates specific elements from the other, always optimizing for clear instruction towards the desired output style.
- If generating two children, try to make them distinct from each other and from the parents, perhaps by emphasizing different aspects of the parental combination that could lead to the desired output style.

All generated prompts must aim for eliciting answers in the style: '{style}'.

Return a JSON object that is a list of both child prompts. Each child prompt is a list of LLM messages. Example:
[
    {{"role": "<role>", "content": "<content>"}},
    {{"role": "<role>", "content": "<content>"}}
]


"""


def radical_innovation_system_prompt(output_style_guidance: str | None) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""You are an expert prompt engineer and a creative problem solver.
Given a task description and an existing prompt for that task (which might be underperforming), your goal is to generate a new, significantly improved, and potentially very different prompt.
Do not just make minor edits. Think about alternative approaches, structures, and phrasings that could lead to better performance.
Consider clarity, specificity, constraints, and how to best guide the language model for the described task TO PRODUCE OUTPUTS IN THE FOLLOWING STYLE: '{style}'.
Return only the new prompt string, with no preamble or explanation.
"""


def llm_crossover_user_prompt(
    parent1_messages: list[dict[str, str]],
    parent2_messages: list[dict[str, str]],
    output_style_guidance: str | None,
) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""Parent Prompt 1:
'''{parent1_messages}'''

Parent Prompt 2:
'''{parent2_messages}'''

Desired output style from target LLM for children prompts: '{style}'

Please generate TWO child prompts by intelligently blending the ideas, styles, or structures from these two parents, ensuring the children aim to elicit the desired output style.
Follow the instructions provided in the system prompt regarding the JSON output format:
[
    {{"role": "<role>", "content": "<content>"}}, {{"role": "<role>", "content": "<content>"}}
]
"""


def mutation_strategy_prompts(output_style_guidance: str | None) -> dict[str, str]:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return {
        "rephrase": (
            "Create a different way to express the same instruction, possibly with a different "
            "length or structure, ensuring it still aims for an answer from the target LLM in the style of: "
            f"'{style}'."
        ),
        "simplify": (
            "Simplify the instruction while maintaining its core meaning, potentially making it more concise, "
            "to elicit an answer in the style of: "
            f"'{style}'."
        ),
        "elaborate": (
            "Add more relevant detail and specificity to the instruction, potentially increasing its length, "
            "but only if it helps achieve a more accurate answer from the target LLM in the style of: "
            f"'{style}'."
        ),
        "restructure": (
            "Change the structure of the instruction (e.g., reorder sentences, combine/split ideas) while keeping its intent, ensuring the new structure strongly guides towards an output in the style of: "
            f"'{style}'."
        ),
        "focus": (
            "Emphasize the key aspects of the instruction, perhaps by rephrasing or adding clarifying statements, "
            "to better elicit an answer in the style of: "
            f"'{style}'."
        ),
        "increase_complexity_and_detail": (
            "Significantly elaborate on this instruction. Add more details, examples, context, or constraints to make it more comprehensive. "
            "The goal of this elaboration is to make the prompt itself more detailed, so that it VERY CLEARLY guides the target LLM to produce a highly accurate final answer in the style of: "
            f"'{style}'. The prompt can be long if needed to achieve this output style."
        ),
    }


# ---------------------------------------------------------------------------
# MCP prompts
# ---------------------------------------------------------------------------


def mcp_tool_rewrite_system_prompt() -> str:
    return (
        "You are an expert prompt engineer tasked with refining MCP tool descriptions. "
        "Always respond with strictly valid JSON matching the requested schema."
    )


def mcp_tool_rewrite_user_prompt(
    *,
    tool_name: str,
    current_description: str,
    tool_metadata_json: str,
    num_variations: int,
) -> str:
    current_description = current_description.strip() or "(no description provided)"
    return f"""You are improving the description of the MCP tool `{tool_name}`.

Current description:
---
{current_description}
---

Tool metadata (JSON):
{tool_metadata_json}

Generate {num_variations} improved descriptions for this tool. Each description should:
- Clarify expected arguments and their semantics.
- Explain how the tool output should be used in the final response.
- Avoid changing the tool name or introducing unsupported behaviour.

Respond strictly as JSON of the form:
{{
  "prompts": [
    {{
      "tool_description": "...",
      "improvement_focus": "..."
    }}
  ]
}}
"""


def semantic_mutation_user_prompt(
    prompt_messages: list[dict[str, str]],
    task_description: str,
    output_style_guidance: str | None,
    strategy_instruction: str,
) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""Given this prompt: '{prompt_messages}'
Task context: {task_description}
Desired output style from target LLM: '{style}'
Instruction for this modification: {strategy_instruction}.
Return only the modified prompt message list, nothing else. Make sure to return a valid JSON object.
"""


def radical_innovation_user_prompt(
    task_description: str,
    output_style_guidance: str | None,
    existing_prompt_messages: list[dict[str, str]],
) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""Task Context:
{task_description}
Desired output style from target LLM: '{style}'

Existing Prompt (which may be underperforming):
'''{existing_prompt_messages}'''

Please generate a new, significantly improved, and potentially very different prompt for this task.
Focus on alternative approaches, better clarity, or more effective guidance for the language model, aiming for the desired output style.
Return only the new prompt list object.
"""


def fresh_start_user_prompt(
    task_description: str,
    output_style_guidance: str | None,
    num_to_generate: int,
) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""Here is a description of a task: ```{task_description}```

The goal is to generate prompts that will make a target LLM produce responses in the following style: ```{style}```.

Please generate {num_to_generate} diverse and effective prompt(s) for a language model to accomplish this task, ensuring they guide towards this specific output style.
Focus on clarity, completeness, and guiding the model effectively towards the desired style. Explore different structural approaches.

Example of valid response: [
    ["role": "<role>", "content": "<Prompt targeting specified style.>"],
    ["role": "<role>", "content": "<Another prompt designed for the output style.>"]
]

Your response MUST be a valid JSON list of AI messages. Do NOT include any other text, explanations, or Markdown formatting like ```json ... ``` around the list.
"""


def variation_user_prompt(
    initial_prompt_messages: list[dict[str, str]],
    task_description: str,
    output_style_guidance: str | None,
    num_variations: int,
) -> str:
    style = (
        output_style_guidance
        or "Produce clear, effective, and high-quality responses suitable for the task."
    )
    return f"""Initial prompt:'''{initial_prompt_messages}'''
Task context: ```{task_description}```
Desired output style from target LLM: '{style}'

Generate {num_variations} diverse alternative prompts based on the initial prompt above, keeping the task context and desired output style in mind.
All generated prompt variations should strongly aim to elicit answers from the target LLM matching the style: '{style}'.
For each variation, consider how to best achieve this style, e.g., by adjusting specificity, structure, phrasing, constraints, or by explicitly requesting it.

Return a JSON array of prompts with the following structure:
{{
    "prompts": [
        {{
            "prompt": [{{"role": "<role>", "content": "<content>"}}],
            "strategy": "brief description of the variation strategy used, e.g., 'direct instruction for target style'"
        }}
        // ... more prompts if num_variations > 1
    ]
}}
Ensure a good mix of variations, all targeting the specified output style from the end LLM.

Return a valid JSON object that is correctly escaped. Return nothing else, do not include any additional text or Markdown formatting.
"""
