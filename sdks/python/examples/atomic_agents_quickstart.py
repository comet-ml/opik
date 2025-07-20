from __future__ import annotations

# Compatibility with latest Atomic Agents (>=1.1)
from atomic_agents.agents.base_agent import BaseAgent as BaseChatAgent
from atomic_agents.agents.base_agent import BaseAgentConfig as BaseChatAgentConfig
from atomic_agents.lib.base.base_io_schema import BaseIOSchema

# Minimal Instructor client (no actual LLM calls) to satisfy BaseAgentConfig
from instructor.client import Instructor
from opik.integrations.atomic_agents import OpikContextProvider, track_atomic_agents

from pydantic import Field

# %% [markdown]
"""
# Atomic Agents × Opik – Quick-Start
Minimal, self-contained example showing how to trace Atomic Agents workflows
with Opik.  You can run this file as a Jupyter **Python Interactive** script
or execute it as plain Python – in both cases the trace will appear in the Opik
UI (assuming you configured the SDK environment variables).
"""

# %% [markdown]
"""## Installation (commented for CI)
```bash
pip install "opik[atomic_agents]" atomic-agents
```
"""

# %% Enable Opik auto-tracking once
track_atomic_agents(project_name="atomic-quickstart")


# %% Define simple echo agent
class ChatMessage(BaseIOSchema):
    """A simple chat message for input and output"""

    chat_message: str = Field(..., description="User message")


class EchoAgent(BaseChatAgent):
    """Agent that echoes back the input message."""

    input_schema = ChatMessage
    output_schema = ChatMessage

    # No need to override run() - BaseAgent handles the workflow
    # The LLM client will be called automatically with the proper schemas


# %% Instantiate & run agent
# Create dummy client that returns an echo response
dummy_instructor_client = Instructor(
    client=None,
    create=lambda **kwargs: ChatMessage(chat_message="Echo: Hello Atomic Agents!"),
)

agent = EchoAgent(
    config=BaseChatAgentConfig(model="gpt-3.5-turbo", client=dummy_instructor_client),
)

result = agent.run(ChatMessage(chat_message="Hello Atomic Agents!"))
print("Agent result:", result)

# %% Attach context provider (optional, demonstration only)
provider = OpikContextProvider(
    project_name="atomic-quickstart", trace_id="<replace-with-id>"
)
print("\nProvider renders:\n", provider.get_info())
