import os, litellm
from litellm.integrations.opik.opik import OpikLogger

# INJECT_OPIK_CONFIGURATION

litellm.callbacks = [OpikLogger()]
response = litellm.completion(
    model="gemini/gemini-pro",
    messages=[{"role": "user", "content": "Write a haiku about AI engineering."}],
)
print(response.choices[0].message.content)
