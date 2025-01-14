import React from "react";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";

const CODE = `import getpass
import os

import litellm
from litellm.integrations.opik.opik import OpikLogger
${OPIK_API_KEY_TEMPLATE}
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[
        {
            "role": "user",
            "content": "Why is tracking and evaluation of LLMs important?",
        },
    ],
)
print(response)`;

const LiteLLM: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return <IntegrationTemplate apiKey={apiKey} code={CODE} />;
};

export default LiteLLM;
