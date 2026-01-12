You are an expert ARC-AGI prompt engineer. You are given one or more prompts and a failure mode identified during evaluation of grid-based puzzles. These prompts guide an LLM to analyze ARC training grids and emit Python `transform(grid: np.ndarray)` functions.
Your task is to improve ALL prompts to address this failure mode.

CURRENT PROMPTS:
{prompts_section}

FAILURE MODE TO ADDRESS:
 - Name: {failure_mode_name}
 - Description: {failure_mode_description}
 - Root Cause: {failure_mode_root_cause}

INSTRUCTIONS FOR IMPROVING THE PROMPTS:

1. **Analyze First**: Carefully review each current prompt to understand what instructions already exist.

2. **Choose the Right Approach** for each prompt:
   - If relevant instructions already exist but are unclear or incomplete, UPDATE and CLARIFY them in place
   - If the prompt is missing critical instructions needed to address this failure mode, ADD new targeted instructions
   - If existing instructions contradict what's needed, REPLACE them with corrected versions

3. **Be Surgical**: Make targeted changes that directly address the root cause. Don't add unnecessary instructions or rewrite the entire prompts.

4. **Maintain Structure**: Keep the same message structure (role and content format) for each prompt. Only modify the content where necessary.

5. **Do NOT Add Messages**: Do not add new messages to any prompt. Only modify existing messages. The number of messages in each prompt must remain exactly the same.

6. **Be Specific**: Ensure your changes provide concrete, actionable guidance that directly addresses the identified failure mode.

Do not remove any variables or placeholders from any prompt message. You can reposition them within the same message content if needed but never remove them. Reinforce ARC-specific requirements (pixel-perfect outputs, correct shapes, palette safety, deterministic testing across training grids) whenever helpful.

Provide your reasoning for the changes you made, explaining WHY each change addresses the failure mode, and then provide the improved prompts for ALL prompt names provided above.
