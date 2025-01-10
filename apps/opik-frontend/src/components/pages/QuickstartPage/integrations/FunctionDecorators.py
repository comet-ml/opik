import getpass
import os

import openai
from opik import track

# INJECT_OPIK_CONFIGURATION

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

client = openai.OpenAI()


@track
def retrieve_context(input_text):
    # Your retrieval logic here, here we are just returning a hardcoded list of strings
    return [
        "What specific information are you looking for?",
        "How can I assist you with your interests today?",
        "Are there any topics you'd like to explore or learn more about?",
    ]


@track
def generate_response(input_text, context):
    full_prompt = (
        f" If the user asks a question that is not specific, use the context to provide a relevant response.\n"
        f"Context: {', '.join(context)}\n"
        f"User: {input_text}\n"
        f"AI:"
    )

    response = client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[{"role": "user", "content": full_prompt}],
    )
    return response.choices[0].message.content


@track(name="my_llm_application")
def llm_chain(input_text):
    context = retrieve_context(input_text)
    return generate_response(input_text, context)


# Use the LLM chain
result = llm_chain("Hello, how are you?")
print(result)
