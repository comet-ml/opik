import React from "react";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE = `import getpass
import os

import anthropic
from opik.integrations.anthropic import track_anthropic
${OPIK_API_KEY_TEMPLATE}
if "ANTHROPIC_API_KEY" not in os.environ:
    os.environ["ANTHROPIC_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

anthropic_client = anthropic.Anthropic()

anthropic_client = track_anthropic(anthropic_client)

PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with Anthropic LLM Models?"

response = anthropic_client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[
        {"role": "user", "content": PROMPT},
    ],
)
print("Response", response.content[0].text)`;

const Anthropic: React.FC<FrameworkIntegrationComponentProps> = ({
  apiKey,
}) => {
  return <IntegrationTemplate apiKey={apiKey} code={CODE} />;
};

export default Anthropic;
