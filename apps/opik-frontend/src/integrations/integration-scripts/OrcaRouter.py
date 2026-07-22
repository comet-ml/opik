from openai import OpenAI
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.openai import track_openai  # HIGHLIGHTED_LINE

configure(project_name="PROJECT_NAME_PLACEHOLDER")  # HIGHLIGHTED_LINE

# Initialize the OpenAI client with the OrcaRouter base URL
client = OpenAI(
    base_url="https://api.orcarouter.ai/v1",
    # api_key="sk-orca-...",  # Get a key at https://www.orcarouter.ai/console
)

# Track all OrcaRouter API calls with Opik
client = track_openai(client)  # HIGHLIGHTED_LINE

# Make a chat completion call. Use orcarouter/auto to let OrcaRouter pick the
# best upstream per request, or name a specific model (e.g. openai/gpt-4o-mini).
response = client.chat.completions.create(
    model="orcarouter/auto",
    messages=[{"role": "user", "content": "Hello, world!"}],
    temperature=0.7,
    max_tokens=256,
)

print(response.choices[0].message.content)
