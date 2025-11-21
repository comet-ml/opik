"""Prompt templates for the Meta-Prompt Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Meta-reasoning about prompt improvements
- Generating candidate prompt variations
- MCP tool description optimization
"""

import textwrap

# System prompt for the meta-reasoning LLM that generates improved prompts
REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.

        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        Instructions:
        1. If there is a system prompt, prioritize adding instructions there if and only if it makes sense.
        2. DO NOT add any variables or parameters to the prompt you are editing.
        3. You can reuse variables that already exist in the prompt.

        Return a JSON array of prompts with the following structure. Make sure to return a valid
        JSON object with correct use of double quotes and single quotes. JSON keys should be
        double-quoted:
        {
            "prompts": [
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                },
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }"""


def build_candidate_generation_user_prompt(
    current_prompt_messages: str,
    best_score: float,
    history_context: str,
    task_context_str: str,
    analysis_instruction: str,
    metric_focus_instruction: str,
    improvement_point_1: str,
    prompts_per_round: int,
) -> str:
    """Build the user prompt for generating candidate prompt variations.

    Args:
        current_prompt_messages: String representation of the current prompt messages
        best_score: Current best evaluation score
        history_context: Context from previous optimization rounds
        task_context_str: Task-specific context from dataset and metric
        analysis_instruction: Instruction for analyzing the prompt
        metric_focus_instruction: Instruction focusing on metric improvement
        improvement_point_1: First improvement guideline
        prompts_per_round: Number of prompts to generate

    Returns:
        Formatted user prompt string
    """
    return textwrap.dedent(
        f"""
            Current prompt: {current_prompt_messages}
            Current score: {best_score}
            {history_context}
            {task_context_str}

            {analysis_instruction}
            Generate {prompts_per_round} improved versions of this prompt.
            {metric_focus_instruction}
            Each version should aim to:
            {improvement_point_1}
            2. Provide necessary context and constraints (if applicable, without relying on disabled external context).
            3. Guide the model to produce the desired output format suitable for the task.
            4. Remove ambiguity and unnecessary elements.
            5. Maintain conciseness while being complete.

            Return a valid JSON array as specified."""
    ).strip()


def build_mcp_tool_description_user_prompt(
    tool_name: str,
    current_description: str,
    tool_metadata_json: str,
    best_score: float,
    history_context: str,
    prompts_per_round: int,
) -> str:
    """Build the user prompt for generating improved MCP tool descriptions.

    Args:
        tool_name: Name of the tool being optimized
        current_description: Current tool description
        tool_metadata_json: JSON string of tool metadata
        best_score: Current best evaluation score
        history_context: Context from previous optimization rounds
        prompts_per_round: Number of descriptions to generate

    Returns:
        Formatted user prompt string
    """
    return textwrap.dedent(
        f"""
            Current tool name: {tool_name}
            Current tool description:
            ---
            {current_description}
            ---

            Tool metadata (JSON):
            {tool_metadata_json}

            Current best score: {best_score:.4f}
            {history_context}

            Generate {prompts_per_round} improved descriptions for this tool.
            Each description should clarify expected input arguments and set explicit expectations
            for how the tool output must be used in the final response.
            Avoid changing unrelated parts of the prompt. Focus only on the description text for `{tool_name}`.

            Return a JSON object of the form:
            {{
              "prompts": [
                {{
                  "tool_description": "...",
                  "improvement_focus": "...",
                  "reasoning": "..."
                }}
              ]
            }}
            """
    ).strip()

