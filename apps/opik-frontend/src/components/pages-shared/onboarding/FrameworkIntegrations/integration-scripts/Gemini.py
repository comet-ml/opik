import getpass
import os

import litellm
from litellm.integrations.opik.opik import OpikLogger

# INJECT_OPIK_CONFIGURATION

if "GEMINI_API_KEY" not in os.environ:
    os.environ["GEMINI_API_KEY"] = getpass.getpass("Enter your Gemini API key: ")

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gemini/gemini-pro",
    messages=[
        {
            "role": "user",
            "content": "Why is tracking and evaluation of LLMs important?",
        },
    ],
)
print(response)
