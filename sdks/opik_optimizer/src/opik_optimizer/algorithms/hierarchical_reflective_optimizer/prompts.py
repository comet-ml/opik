"""Prompt templates for the Hierarchical Reflective Optimizer.

This module contains all the prompt templates used by the optimizer for:
- Batch-level root cause analysis
- Synthesis of batch analyses
- Prompt improvement generation
"""

# Prompt template for analyzing a batch of test results
BATCH_ANALYSIS_PROMPT = """You are analyzing evaluation results to identify failure patterns.

TEST RESULTS:
```
{formatted_batch}
```

Important constraint: Base your analysis exclusively on the TEST RESULTS shown above. Do not infer, speculate, or hypothesize failure modes that are not directly evidenced in the provided results.

Think through the failures systematically:

1. IDENTIFY: List all distinct types of failures you observe in the test results
2. GROUP: Which failures share similar characteristics or root causes?
3. FREQUENCY: Which patterns appear multiple times across different test cases?
4. PRIORITIZE: Which failures are most critical to address?

Then, for each distinct failure pattern provide:
1. A clear, descriptive name that captures the essence of the failure
2. A comprehensive description of what is failing
3. The underlying root cause explaining why this failure occurs

Focus on patterns that appear multiple times. Be specific about what is failing and why.
Provide a list of failure modes, each with a name, description, and root cause."""


# Prompt template for synthesizing multiple batch analyses
SYNTHESIS_PROMPT = """You are synthesizing root cause analyses from multiple batches of evaluation results.

BATCH ANALYSES:
```
{batch_summaries}
```

Your task is to synthesize these batch-level analyses into a unified root cause analysis.

1. MERGE similar failure modes across batches:
   - If multiple batches identify the same or very similar failure pattern, combine them into one unified failure mode
   - Create a comprehensive description that captures the pattern across all relevant batches
   - Identify the core root cause

2. PRIORITIZE the most critical failure modes:
   - Focus on patterns that appear in multiple batches
   - Consider the severity and frequency of each failure
   - Eliminate one-off or minor issues unless they're particularly impactful

3. PROVIDE SYNTHESIS NOTES:
   - Briefly explain which batch-level patterns were merged and why
   - Note any cross-batch trends or patterns
   - Highlight the most critical areas for improvement

Provide:
1. A unified list of failure modes (name, description, root cause)
2. Synthesis notes explaining your analysis process and key findings"""


# Prompt template for improving prompts based on failure modes
IMPROVE_PROMPT_TEMPLATE = """You are an expert prompt engineer. You are given a prompt and a failure mode identified during evaluation.
Your task is to improve the prompt to address this failure mode.

CURRENT PROMPT:
```
{current_prompt}
```
FAILURE MODE TO ADDRESS:
 - Name: {failure_mode_name}
 - Description: {failure_mode_description}
 - Root Cause: {failure_mode_root_cause}

INSTRUCTIONS FOR IMPROVING THE PROMPT:

1. **Analyze First**: Carefully review the current prompt to understand what instructions already exist.

2. **Choose the Right Approach**:
   - If relevant instructions already exist but are unclear or incomplete, UPDATE and CLARIFY them in place
   - If the prompt is missing critical instructions needed to address this failure mode, ADD new targeted instructions
   - If existing instructions contradict what's needed, REPLACE them with corrected versions

3. **Be Surgical**: Make targeted changes that directly address the root cause. Don't add unnecessary instructions or rewrite the entire prompt.

4. **Maintain Structure**: Keep the same message structure (role and content format). Only modify the content where necessary.

5. **Do NOT Add Messages**: Do not add new messages to the prompt. Only modify existing messages. The number of messages in the prompt must remain exactly the same.

6. **Be Specific**: Ensure your changes provide concrete, actionable guidance that directly addresses the identified failure mode.

Do not remove any variables or placeholders from any prompt message. You can reposition them within the same message content if needed but never remove them.

Provide your reasoning for the changes you made, explaining WHY each change addresses the failure mode, and then provide the improved prompt."""
