import React from "react";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";

const CODE_TITLE =
  "You can use the `OpikConnector` provided by the Opik SDK to log all Haystack calls to Opik:";

const CODE = `import getpass
import os

os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

from haystack import Pipeline
from haystack.components.builders import ChatPromptBuilder
from haystack.components.generators.chat import OpenAIChatGenerator
from haystack.dataclasses import ChatMessage
from opik.integrations.haystack import OpikConnector
${OPIK_API_KEY_TEMPLATE}
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

pipe = Pipeline()

# Add the OpikConnector component to the pipeline
pipe.add_component(
    "tracer",
    OpikConnector("Chat example"),
)

# Continue building the pipeline
pipe.add_component("prompt_builder", ChatPromptBuilder())
pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

pipe.connect("prompt_builder.prompt", "llm.messages")

messages = [
    ChatMessage.from_system(
        "Always respond in German even if some input data is in other languages.",
    ),
    ChatMessage.from_user("Tell me about {{location}}"),
]

response = pipe.run(
    data={
        "prompt_builder": {
            "template_variables": {"location": "Berlin"},
            "template": messages,
        },
    },
)

print(response["llm"]["replies"][0])`;

const Haystack: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default Haystack;
