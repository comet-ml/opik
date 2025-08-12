from opik.integrations.openai import track_openai # HIGHLIGHTED_LINE
from openai import OpenAI # HIGHLIGHTED_LINE
from opik import configure # HIGHLIGHTED_LINE

configure()

# Create the OpenAI client that points to DeepSeek API
client = OpenAI(
    api_key="<DeepSeek API Key>", 
    base_url="https://api.deepseek.com"
)

# Wrap your OpenAI client to track all calls to Opik
client = track_openai(client) # HIGHLIGHTED_LINE

# Call the API
response = client.chat.completions.create(
    model="deepseek-chat",
    messages=[
        {"role": "system", "content": "You are a helpful assistant"},
        {"role": "user", "content": "Hello"},
    ],
    stream=False
)

print(response.choices[0].message.content)
