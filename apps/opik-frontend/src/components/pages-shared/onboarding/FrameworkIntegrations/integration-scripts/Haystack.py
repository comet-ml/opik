import os

os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true" # HIGHLIGHTED_LINE

# INJECT_OPIK_CONFIGURATION

from haystack import Pipeline
from haystack.components.builders import ChatPromptBuilder
from haystack.components.generators.chat import OpenAIChatGenerator
from haystack.dataclasses import ChatMessage
from opik.integrations.haystack import OpikConnector # HIGHLIGHTED_LINE

pipe = Pipeline()
pipe.add_component("tracer", OpikConnector("Chat example")) # HIGHLIGHTED_LINE
pipe.add_component("prompt_builder", ChatPromptBuilder())
pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))
pipe.connect("prompt_builder.prompt", "llm.messages")
messages = [
    ChatMessage.from_user("Write a haiku about AI engineering."),
]
response = pipe.run(data={"prompt_builder": {"template": messages}})
print(response["llm"]["replies"][0].text)
