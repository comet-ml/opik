from openai import OpenAI
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.openai import track_openai  # HIGHLIGHTED_LINE

configure(project_name="PROJECT_NAME_PLACEHOLDER")  # HIGHLIGHTED_LINE

# Initialize the OpenAI client with Requesty base URL
client = OpenAI(
    base_url="https://router.requesty.ai/v1",
    # api_key="YOUR_REQUESTY_API_KEY",
)

# Track all Requesty API calls with Opik
client = track_openai(client)  # HIGHLIGHTED_LINE

# Optional analytics headers
headers = {
    "HTTP-Referer": "YOUR_SITE_URL",  # Optional. Site URL for analytics
    "X-Title": "YOUR_SITE_NAME",  # Optional. Site title for analytics
}

# Make a chat completion call
response = client.chat.completions.create(
    model="openai/gpt-4o-mini",  # You can use any model available on Requesty
    extra_headers=headers,
    messages=[{"role": "user", "content": "Hello, world!"}],
    temperature=0.7,
    max_tokens=100,
)

print(response.choices[0].message.content)
