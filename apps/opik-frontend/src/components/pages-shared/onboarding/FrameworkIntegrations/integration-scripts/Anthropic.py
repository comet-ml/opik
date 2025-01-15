import getpass
import os

import anthropic
from opik.integrations.anthropic import track_anthropic

# INJECT_OPIK_CONFIGURATION

if "ANTHROPIC_API_KEY" not in os.environ:
    os.environ["ANTHROPIC_API_KEY"] = getpass.getpass("Enter your Anthropic API key: ")

anthropic_client = anthropic.Anthropic()

anthropic_client = track_anthropic(anthropic_client)

PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with Anthropic LLM Models?"

response = anthropic_client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[
        {"role": "user", "content": PROMPT},
    ],
)
print("Response", response.content[0].text)
