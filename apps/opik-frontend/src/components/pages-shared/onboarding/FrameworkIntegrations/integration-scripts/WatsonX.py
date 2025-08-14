import os

import litellm
import opik  # HIGHLIGHTED_LINE
from litellm.integrations.opik.opik import OpikLogger  # HIGHLIGHTED_LINE
from opik import track  # HIGHLIGHTED_LINE
from opik.opik_context import get_current_span_data  # HIGHLIGHTED_LINE

opik.configure()  # HIGHLIGHTED_LINE

# Configure WatsonX environment variables
os.environ["WATSONX_ENDPOINT_URL"] = ""  # Base URL of your WatsonX instance
os.environ["WATSONX_API_KEY"] = ""  # IBM cloud API key
os.environ["WATSONX_TOKEN"] = ""  # IAM auth token

# Optional
# os.environ["WATSONX_PROJECT_ID"] = ""  # Project ID of your WatsonX instance

# Create OpikLogger callback and add to LiteLLM
opik_logger = OpikLogger()  # HIGHLIGHTED_LINE
litellm.callbacks = [opik_logger]  # HIGHLIGHTED_LINE

# Simple LLM call
response = litellm.completion(
    model="watsonx/ibm/granite-13b-chat-v2",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ],
)


# Example of tracked function with WatsonX
@track  # HIGHLIGHTED_LINE
def generate_story(prompt):
    response = litellm.completion(
        model="watsonx/ibm/granite-13b-chat-v2",
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track  # HIGHLIGHTED_LINE
def generate_opik_story():
    topic = "Generate a topic for a story about Opik."
    story = generate_story(topic)
    return story


# Run the tracked function
generate_opik_story()
