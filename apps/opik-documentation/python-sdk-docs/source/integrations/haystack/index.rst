Haystack
========

Opik integrates with Haystack to allow you to log your Haystack pipeline runs to the Opik platform, simply wrap the Haystack pipeline with `OpikConnector` to start logging::

   import os

    os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

    from haystack import Pipeline
    from haystack.components.builders import ChatPromptBuilder
    from haystack.components.generators.chat import OpenAIChatGenerator
    from haystack.dataclasses import ChatMessage

    from opik.integrations.haystack import OpikConnector


    pipe = Pipeline()

    # Add the OpikConnector component to the pipeline
    pipe.add_component(
        "tracer", OpikConnector("Chat example")
    )

    # Continue building the pipeline
    pipe.add_component("prompt_builder", ChatPromptBuilder())
    pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

    pipe.connect("prompt_builder.prompt", "llm.messages")

    messages = [
        ChatMessage.from_system(
            "Always respond in German even if some input data is in other languages."
        ),
        ChatMessage.from_user("Tell me about {{location}}"),
    ]

    response = pipe.run(
        data={
            "prompt_builder": {
                "template_variables": {"location": "Berlin"},
                "template": messages,
            }
        }
    )

You can learn more about the `OpikConnector` in the following section:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   OpikConnector
