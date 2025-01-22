import os, openai
from opik import track

# INJECT_OPIK_CONFIGURATION

client = openai.OpenAI()

@track
def retrieve_context(input_text):
    return [
        "What specific information are you looking for?",
        "How can I assist you with your interests today?",
        "Are there any topics you'd like to explore or learn more about?",
    ]

@track
def generate_response(input_text, context):
    full_prompt = f'If the user asks a question that is not specific, use the context to provide a relevant response.\nContext: {", ".join(context)}\nUser: {input_text}\nAI:'
    response = client.chat.completions.create(
        model="gpt-3.5-turbo", messages=[{"role": "user", "content": full_prompt}]
    )
    return response.choices[0].message.content

context = retrieve_context("Hi how are you?")
print(generate_response("Hi how are you?", context))
