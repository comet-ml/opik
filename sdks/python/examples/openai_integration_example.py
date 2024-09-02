from openai import OpenAI
from opik import flush_tracker, track
from opik.integrations.openai import opik_tracker

# os.environ["OPENAI_ORG_ID"] = "YOUR OPENAI ORG ID"
# os.environ["OPENAI_API_KEY"] = "YOUR OPENAI API KEY"

client = OpenAI()

client = opik_tracker.track_openai(client)


@track()
def f_with_streamed_openai_call():
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    # will create one more nested span, its output will
    # be updated once stream generator is exhausted
    stream = client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
    )

    for item in stream:
        print(item)


@track()
def f_with_usual_chat_completion_call():
    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    # will create one more nested span
    _ = client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=messages,
        max_tokens=10,
    )


f_with_streamed_openai_call()  # trace 1
f_with_usual_chat_completion_call()  # trace 2
_ = client.chat.completions.create(  # trace 3
    model="gpt-3.5-turbo",
    messages=[
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ],
    max_tokens=10,
)

flush_tracker()
