import os

from langchain_community.llms import Predibase
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.langchain import OpikTracer  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["PREDIBASE_API_TOKEN"] = "your-api-key-here"

# Create Predibase model
model = Predibase(
    model="mistral-7b",
    predibase_api_key=os.environ.get("PREDIBASE_API_TOKEN"),
)

# Create Opik tracer
opik_tracer = OpikTracer(tags=["predibase", "mistral-7b"])  # HIGHLIGHTED_LINE

# Test the model with Opik tracing
response = model.invoke(
    "Can you recommend me a nice dry wine?",
    config={
        "temperature": 0.5,
        "max_new_tokens": 1024,
        "callbacks": [opik_tracer],  # HIGHLIGHTED_LINE
    },
)

print(response)
