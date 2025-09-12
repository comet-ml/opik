from ..optimization_config import chat_prompt


class Helpers:
    def _get_task_description_for_llm(self, prompt: chat_prompt.ChatPrompt) -> str:
        """Generates a concise task description for LLM prompts that need context."""
        description = "Task: Given a list of AI messages with placeholder values, generate an effective prompt. "
        description += f"The original high-level instruction being optimized is: '{prompt.get_messages()}'. "
        description += "The goal is to create an effective prompt that guides a language model to perform this task well."
        return description
