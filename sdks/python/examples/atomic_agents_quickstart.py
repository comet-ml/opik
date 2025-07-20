from __future__ import annotations
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

# %% Imports
# from __future__ import annotations  # Moved to file top

from pydantic import BaseModel, Field

# Compatibility with latest Atomic Agents (>=1.1)
from atomic_agents.agents.base_agent import BaseAgent as BaseChatAgent, BaseAgentConfig as BaseChatAgentConfig
from opik.integrations.atomic_agents import (
    OpikContextProvider,
    track_atomic_agents,
)

# Minimal Instructor client (no actual LLM calls) to satisfy BaseAgentConfig
from instructor.client import Instructor


dummy_instructor_client = Instructor(client=None, create=lambda **kwargs: None)

# %% Enable Opik auto-tracking once
track_atomic_agents(project_name="atomic-quickstart")

# %% Define simple echo agent
class InputSchema(BaseModel):
    chat_message: str = Field(..., description="User message")


class OutputSchema(BaseModel):
    chat_message: str


class EchoAgent(BaseChatAgent):
    input_schema = InputSchema
    output_schema = OutputSchema

    def run(self, inp: InputSchema) -> OutputSchema:  # type: ignore[override]
        """Return user message unchanged."""
        return OutputSchema(chat_message=inp.chat_message)


# %% Instantiate & run agent
agent = EchoAgent(
    config=BaseChatAgentConfig(model="gpt-3.5-turbo", client=dummy_instructor_client),
)

result = agent.run(InputSchema(chat_message="Hello Atomic Agents!"))
print("Agent result:", result)

# %% Attach context provider (optional, demonstration only)
provider = OpikContextProvider(project_name="atomic-quickstart", trace_id="<replace-with-id>")
print("\nProvider renders:\n", provider.get_info()) 