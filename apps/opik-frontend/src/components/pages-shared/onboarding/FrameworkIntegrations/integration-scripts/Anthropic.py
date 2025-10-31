import os

import anthropic
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.anthropic import track_anthropic  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["ANTHROPIC_API_KEY"] = "your-api-key-here"

anthropic_client = anthropic.Anthropic()
anthropic_client = track_anthropic(anthropic_client)  # HIGHLIGHTED_LINE
response = anthropic_client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Write a haiku about AI engineering."}],
)
print("Response", response.content[0].text)
