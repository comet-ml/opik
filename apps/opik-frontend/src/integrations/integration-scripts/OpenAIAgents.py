import asyncio
import os
import uuid

from agents import Agent, Runner, set_trace_processors, trace
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.openai.agents import OpikTracingProcessor  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["OPENAI_API_KEY"] = "YOUR_OPENAI_API_KEY"

set_trace_processors(processors=[OpikTracingProcessor()])  # HIGHLIGHTED_LINE


async def main():
    agent = Agent(name="Assistant", instructions="Reply very concisely.")

    thread_id = str(uuid.uuid4())

    with trace(workflow_name="Conversation", group_id=thread_id):
        # First turn
        result = await Runner.run(agent, "What city is the Golden Gate Bridge in?")
        print(result.final_output)
        # San Francisco

        # Second turn
        new_input = result.to_input_list() + [
            {"role": "user", "content": "What state is it in?"}
        ]
        result = await Runner.run(agent, new_input)
        print(result.final_output)
        # California


if __name__ == "__main__":
    asyncio.run(main())
