import getpass
import os

import litellm
from litellm.integrations.opik.opik import OpikLogger

# os.environ["OPIK_API_KEY"] = "{TODO_REPLACE_ME}"

if "GEMINI_API_KEY" not in os.environ:
    os.environ["GEMINI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

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
