import os

from openai import OpenAI
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.openai import track_openai  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["OPENAI_API_KEY"] = "your-api-key-here"

openai_client = track_openai(OpenAI())  # HIGHLIGHTED_LINE
prompt = "Write a haiku about AI engineering."
response = openai_client.chat.completions.create(
    model="gpt-3.5-turbo",
    messages=[{"role": "user", "content": prompt}],
)
print(response.choices[0].message.content)
