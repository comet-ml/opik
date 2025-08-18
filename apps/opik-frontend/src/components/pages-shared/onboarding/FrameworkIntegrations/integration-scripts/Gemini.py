import os

import google.genai
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.genai import track_genai  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# os.environ["GEMINI_API_KEY"] = "your-api-key-here"

client = google.genai.Client()
gemini_client = track_genai(client)  # HIGHLIGHTED_LINE
response = gemini_client.models.generate_content(
    model="gemini-2.0-flash-001", contents="Write a haiku about AI engineering."
)
print(response.text)
