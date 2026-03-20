from autogen import AssistantAgent, UserProxyAgent, LLMConfig
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.ag2 import OpikInstrumentor  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# Initialize the Opik instrumentor — LLM calls are auto-instrumented
instrumentor = OpikInstrumentor(project_name="ag2-demo")  # HIGHLIGHTED_LINE

# Define the LLM configuration
llm_config = LLMConfig(api_type="openai", model="gpt-4o-mini")

# Create agents
with llm_config:
    researcher = AssistantAgent(
        name="Researcher",
        system_message="You are a research assistant. Find key facts and data.",
    )
    writer = AssistantAgent(
        name="Writer",
        system_message="You are a writer. Summarize research into clear prose.",
    )

# Instrument each agent for tracing
instrumentor.instrument_agent(researcher)  # HIGHLIGHTED_LINE
instrumentor.instrument_agent(writer)  # HIGHLIGHTED_LINE

# Create a user proxy to drive the conversation
user = UserProxyAgent(
    name="User",
    human_input_mode="NEVER",
    code_execution_config=False,
)
instrumentor.instrument_agent(user)  # HIGHLIGHTED_LINE

# Start a multi-agent conversation
result = user.initiate_chat(
    researcher,
    message="What are the benefits of renewable energy?",
    max_turns=3,
)
print(result.summary)
