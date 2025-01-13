import React from "react";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import IntegrationTemplate from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";

const CODE_TITLE =
  "Each function decorated by the `track` decorator will be logged to the Opik platform:";

const CODE = `import opik

@opik.track
def my_llm_chain(input_text):
    # Call the different parts of my chain
    return “Hello, world!”`;

const FunctionDecorators: React.FC<FrameworkIntegrationComponentProps> = ({
  apiKey,
}) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default FunctionDecorators;
