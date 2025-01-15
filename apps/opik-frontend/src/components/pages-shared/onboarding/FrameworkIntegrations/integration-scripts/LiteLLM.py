import getpass
import os

import litellm
from litellm.integrations.opik.opik import OpikLogger

# INJECT_OPIK_CONFIGURATION

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[
        {
            "role": "user",
            "content": "Why is tracking and evaluation of LLMs important?",
        },
    ],
)
print(response)
