import os

import litellm
from litellm.integrations.opik.opik import OpikLogger  # HIGHLIGHTED_LINE
from opik import configure  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["GROQ_API_KEY"] = "your-api-key-here"

litellm.callbacks = [OpikLogger()]  # HIGHLIGHTED_LINE
response = litellm.completion(
    model="groq/llama3-8b-8192",
    messages=[{"role": "user", "content": "Write a haiku about AI engineering."}],
)
print(response.choices[0].message.content)
