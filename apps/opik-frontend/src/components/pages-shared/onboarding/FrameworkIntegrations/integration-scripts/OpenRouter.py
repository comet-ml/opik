from openai import OpenAI
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.openai import track_openai  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE

# Initialize the OpenAI client with OpenRouter base URL
client = OpenAI(
    base_url="https://openrouter.ai/api/v1",
    # api_key="YOUR_OPENROUTER_API_KEY",
)

# Track all OpenRouter API calls with Opik
client = track_openai(client)  # HIGHLIGHTED_LINE

# Optional headers for OpenRouter leaderboard
headers = {
    "HTTP-Referer": "YOUR_SITE_URL",  # Optional. Site URL for rankings
    "X-Title": "YOUR_SITE_NAME",  # Optional. Site title for rankings
}

# Make a chat completion call
response = client.chat.completions.create(
    model="openai/gpt-4",  # You can use any model available on OpenRouter
    extra_headers=headers,
    messages=[{"role": "user", "content": "Hello, world!"}],
    temperature=0.7,
    max_tokens=100,
)

print(response.choices[0].message.content)
