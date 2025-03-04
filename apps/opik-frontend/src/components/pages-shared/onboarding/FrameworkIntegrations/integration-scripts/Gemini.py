import os, google.genai
from opik.integrations.genai import track_genai # HIGHLIGHTED_LINE

# INJECT_OPIK_CONFIGURATION

client = google.genai.Client()
gemini_client = track_genai(client) # HIGHLIGHTED_LINE
response = gemini_client.models.generate_content(
    model="gemini-2.0-flash-001", contents="Write a haiku about AI engineering."
)
print(response.text)