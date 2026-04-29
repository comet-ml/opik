import asyncio
from pyagentspec.agent import Agent
from pyagentspec.llms import OpenAiConfig
from pyagentspec.property import FloatProperty
from pyagentspec.tools import ServerTool


def build_agentspec_agent() -> Agent:
    tools = [
        ServerTool(
            name="sum",
            description="Sum two numbers",
            inputs=[FloatProperty(title="a"), FloatProperty(title="b")],
            outputs=[FloatProperty(title="result")],
        ),
        ServerTool(
            name="subtract",
            description="Subtract two numbers",
            inputs=[FloatProperty(title="a"), FloatProperty(title="b")],
            outputs=[FloatProperty(title="result")],
        ),
        ServerTool(
            name="multiply",
            description="Multiply two numbers",
            inputs=[FloatProperty(title="a"), FloatProperty(title="b")],
            outputs=[FloatProperty(title="result")],
        ),
        ServerTool(
            name="divide",
            description="Divide two numbers",
            inputs=[FloatProperty(title="a"), FloatProperty(title="b")],
            outputs=[FloatProperty(title="result")],
        ),
    ]

    return Agent(
        name="calculator_agent",
        description="An agent that provides assistance with tool use.",
        llm_config=OpenAiConfig(name="openai-gpt-5-mini", model_id="gpt-5-mini"),
        system_prompt=(
            "You are a helpful calculator agent.\n"
            "Your duty is to compute the result of the given operation using tools, "
            "and to output the result.\n"
            "It's important that you reply with the result only.\n"
        ),
        tools=tools,
    )


async def main():

    import opik  # HIGHLIGHTED_LINE
    from opik.integrations.agentspec import AgentSpecInstrumentor  # HIGHLIGHTED_LINE
    from pyagentspec.adapters.langgraph import AgentSpecLoader

    opik.configure()  # HIGHLIGHTED_LINE

    agent = build_agentspec_agent()
    tool_registry = {
        "sum": lambda a, b: a + b,
        "subtract": lambda a, b: a - b,
        "multiply": lambda a, b: a * b,
        "divide": lambda a, b: a / b,
    }
    langgraph_agent = AgentSpecLoader(tool_registry=tool_registry).load_component(agent)

    with AgentSpecInstrumentor().instrument_context(  # HIGHLIGHTED_LINE
      project_name="calculator-agent-trace",  # HIGHLIGHTED_LINE
      mask_sensitive_information=False,  # HIGHLIGHTED_LINE
    ):  # HIGHLIGHTED_LINE
        messages = []
        while True:
            user_input = input("USER  >>> ")
            if user_input.lower() in ["exit", "quit"]:
                break
            messages.append({"role": "user", "content": user_input})
            response = langgraph_agent.invoke(
                input={"messages": messages},
                config={"configurable": {"thread_id": "1"}},
            )
            agent_answer = response["messages"][-1].content.strip()
            print("AGENT >>>", agent_answer)
            messages.append({"role": "assistant", "content": agent_answer})


if __name__ == "__main__":
    asyncio.run(main())
