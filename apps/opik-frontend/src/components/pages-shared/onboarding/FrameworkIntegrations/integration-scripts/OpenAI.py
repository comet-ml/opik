import os
from openai import OpenAI
from opik.integrations.openai import track_openai

# INJECT_OPIK_CONFIGURATION

openai_client = track_openai(OpenAI())
prompt = "Write a haiku about AI engineering."
response = openai_client.chat.completions.create(
    model="gpt-3.5-turbo",
    messages=[{"role": "user", "content": prompt}],
)
print(response.choices[0].message.content)
