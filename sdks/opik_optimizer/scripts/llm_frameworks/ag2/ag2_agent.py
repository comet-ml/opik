from __future__ import annotations

from typing import Any, TYPE_CHECKING

from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik import track
from opik.integrations.ag2 import OpikInstrumentor
from autogen import AssistantAgent, UserProxyAgent, LLMConfig

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt


class AG2Agent(OptimizableAgent):
    project_name = "ag2-agent"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        if len(prompts) > 1:
            raise ValueError("AG2Agent only supports single-prompt optimization.")

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Extract system prompt and user question
        system_text = next(
            (m["content"] for m in messages if m["role"] == "system"), ""
        )
        question = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )

        model_name = prompt.model or "gpt-4o-mini"
        instrumentor = OpikInstrumentor(project_name=self.project_name)

        llm_config = LLMConfig(api_type="openai", model=model_name)

        with llm_config:
            assistant = AssistantAgent(
                name="ResearchAssistant",
                system_message=system_text
                or "An AI assistant specialized in finding accurate information.",
            )

        user = UserProxyAgent(
            name="User",
            human_input_mode="NEVER",
            code_execution_config=False,
        )

        instrumentor.instrument_agent(assistant)
        instrumentor.instrument_agent(user)

        result = user.initiate_chat(
            assistant,
            message=question,
            max_turns=2,
        )

        instrumentor.flush()
        return result.summary if result.summary else str(result.chat_history[-1]["content"] if result.chat_history else "")
