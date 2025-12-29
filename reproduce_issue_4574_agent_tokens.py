"""
Reproduction script for GitHub issue #4574: Token count issue in agentic trajectories.

This script reproduces the bug where token counts are duplicated when using agents with tool calling.

To run this script:
1. Ensure you have the required dependencies installed (see requirements below)
2. Configure your OpenAI API key: export OPENAI_API_KEY=your_key_here
3. (Optional) Configure local Opik: export OPIK_URL_OVERRIDE=http://localhost:5173
4. Run: python reproduce_issue_4574.py

Expected behavior after the fix:
- Token counts should only be attributed to LLM calls
- Tool calls should NOT have token usage
- Total tokens in UI should not be duplicated
"""

import opik
from opik.integrations.langchain import OpikTracer
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.tools import tool


# -------------------------------
# Constants
# -------------------------------
OPIK_LOCAL = True
PROJECT_NAME = "agent_trajectory_test"
LLM_CHAT_MODEL_ID = "gpt-4o-mini"
LLM_TEMPERATURE = 0


# -------------------------------
# Tools
# -------------------------------
@tool
def get_weather(city: str) -> str:
    """Get weather for a given city."""
    return f"{city} - 28 °C"


# -------------------------------
# Main
# -------------------------------
def main():
    # Configure Tracing Opik
    opik.configure(
        use_local=OPIK_LOCAL,
    )

    # Initialize Opik client
    opik_client = opik.Opik()

    # Create the Opik tracer
    opik_tracer = OpikTracer(
        tags=["langchain", "agent", "issue-4574"],
        project_name=PROJECT_NAME
    )

    # Configure LLM client
    llm = ChatOpenAI(
        model=LLM_CHAT_MODEL_ID,
        temperature=LLM_TEMPERATURE,
        max_tokens=100,
        stream_usage=True,  # Enable streaming usage tracking
    )

    # Create agent prompt
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", "You are a helpful assistant"),
            ("human", "{input}"),
            ("placeholder", "{agent_scratchpad}"),
        ]
    )

    # Create the agent
    agent = create_tool_calling_agent(llm, [get_weather], prompt)
    agent_executor = AgentExecutor(
        agent=agent,
        tools=[get_weather],
        verbose=True
    )

    # ---------------------------------
    # Inference
    # ---------------------------------
    question = "what is the weather in sf"
    print(f"\nQuestion: {question}\n")
    
    response = agent_executor.invoke(
        {"input": question},
        config={"callbacks": [opik_tracer]}
    )

    print(f"\nResponse: {response['output']}\n")
    
    # Flush to ensure all data is sent
    opik_tracer.flush()
    
    print("\n" + "="*80)
    print("✅ Script completed successfully!")
    print("="*80)
    print("\nNext steps:")
    print("1. Check the Opik UI for the trace")
    print(f"2. Project: {PROJECT_NAME}")
    print("3. Verify that:")
    print("   - LLM spans have token counts (prompt_tokens, completion_tokens, total_tokens)")
    print("   - Tool spans do NOT have token counts")
    print("   - Total token count in UI is correct (not duplicated)")
    print("="*80 + "\n")


if __name__ == "__main__":
    main()

