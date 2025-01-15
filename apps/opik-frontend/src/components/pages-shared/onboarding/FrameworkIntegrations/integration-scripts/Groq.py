import getpass
import os

import litellm
from litellm.integrations.opik.opik import OpikLogger

# INJECT_OPIK_CONFIGURATION

if "GROQ_API_KEY" not in os.environ:
    os.environ["GROQ_API_KEY"] = getpass.getpass("Enter your Groq API key: ")

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="groq/llama3-8b-8192",
    messages=[
        {
            "role": "user",
            "content": "Why is tracking and evaluation of LLMs important?",
        },
    ],
)
print(response)
