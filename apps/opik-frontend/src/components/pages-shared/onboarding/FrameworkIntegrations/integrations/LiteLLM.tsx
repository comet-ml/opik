import React from "react";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import IntegrationTemplate from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";

const CODE_TITLE = "You can configure LiteLLM to log all LLM calls to Opik:";

const CODE = `from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ]
)`;

const LiteLLM: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default LiteLLM;
