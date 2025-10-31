import logging
from typing import Any, Dict, Optional

import haystack
from haystack import tracing

import opik.api_objects.opik_client as opik_client
import opik.decorator.tracing_runtime_config as tracing_runtime_config
from . import opik_tracer

LOGGER = logging.getLogger(__name__)


@haystack.component
class OpikConnector:
    """
    OpikConnector connects Haystack LLM framework with [Opik](https://github.com/comet-ml/opik) in order to enable the
    tracing of operations and data flow within various components of a pipeline.

    Simply add this component to your pipeline, but *do not* connect it to any other component. The OpikConnector
    will automatically trace the operations and data flow within the pipeline.

    In order to configure Opik, you will need to call first install the Opik SDK using `pip install opik` and then
    run `opik configure` from the command line. Alternatively you can configure Opik using environment variables,
    you can find more information about how to configure Opik [here](https://www.comet.com/docs/opik/tracing/sdk_configuration).

    In addition, you need to set the `HAYSTACK_CONTENT_TRACING_ENABLED` environment variable to `true` in order to
    enable Haystack tracing in your pipeline.

    Example:
        You can use the `OpikConnector` in the following way::

                import os

                os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

                from haystack import Pipeline
                from haystack.components.builders import ChatPromptBuilder
                from haystack.components.generators.chat import OpenAIChatGenerator
                from haystack.dataclasses import ChatMessage
                from opik.integrations.haystack import (
                    OpikConnector,
                )

                if __name__ == "__main__":
                    pipe = Pipeline()
                    pipe.add_component("tracer", OpikConnector("Chat example"))
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
                    print(response["llm"]["replies"][0])
                    print(response["tracer"]["trace_url"])

    Note:

        You may disable flushing the data after each component by setting the `HAYSTACK_OPIK_ENFORCE_FLUSH`
        environent variable to `false`. By default, the data is flushed after each component and blocks the thread until
        the data is sent to Opik. **Caution**: Disabling this feature may result in data loss if the program crashes
        before the data is sent to Opik. Make sure you will call the `flush()` method explicitly before the program exits.
        E.g. by using tracer.actual_tracer.flush()::

            from haystack.tracing import tracer

            tracer.actual_tracer.flush()
    """

    def __init__(self, name: str, project_name: Optional[str] = None):
        """
        Initialize the OpikConnector component.

        Args:
            name: The name of the pipeline or component. This name will be used to identify the tracing run on the
                Opik dashboard.
            project_name: The name of the project to use for the tracing run. If not provided, the project name will be
                set to the default project name.
        """
        self.name = name

        if not tracing_runtime_config.is_tracing_active():
            self.tracer = None
            return

        self.tracer = self._create_opik_tracer(name, project_name)
        tracing.enable_tracing(self.tracer)

    def _create_opik_tracer(
        self, name: str, project_name: Optional[str]
    ) -> opik_tracer.OpikTracer:
        """Create and configure the OpikTracer instance."""
        opik_client_ = opik_client.get_client_cached()

        return opik_tracer.OpikTracer(
            opik_client=opik_client_, name=name, project_name=project_name
        )

    @haystack.component.output_types(name=str, trace_id=Optional[str], project_url=str)
    def run(
        self, invocation_context: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """
        Runs the OpikConnector component.

        Args:
            invocation_context: A dictionary with additional context for the invocation. This parameter
                is useful when users want to mark this particular invocation with additional information, e.g.
                a run id from their own execution framework, user id, etc. These key-value pairs are then visible
                in the Opik traces.

        Returns:
            A dictionary with the following keys:
                - `name`: The name of the tracing component.
                - `trace_id`: The Opik trace id.
                - `project_url`: The URL to the Opik project with tracing data.
        """
        LOGGER.debug(
            "Opik tracer invoked with the following context: %s", invocation_context
        )

        return self._build_response()

    def _build_response(self) -> Dict[str, Any]:
        """Build the response dictionary for the OpikConnector run method."""
        if self.tracer is None:
            return {"name": self.name, "trace_id": None, "project_url": None}

        return {
            "name": self.name,
            "trace_id": self.tracer.get_trace_id(),
            "project_url": self.tracer.get_project_url(),
        }
