import React from "react";
import { IntegrationComponentProps } from "@/components/pages/QuickstartPage/integrations/types";
import pythonLogoUrl from "/images/integrations/python.png";
import IntegrationTemplate from "@/components/pages/QuickstartPage/integrations/IntegrationTemplate";

const CODE_TITLE =
  "Each function decorated by the `track` decorator will be logged to the Opik platform:";

const CODE = `import opik

@opik.track
def my_llm_chain(input_text):
    # Call the different parts of my chain
    return “Hello, world!”`;

const FunctionDecorators: React.FC<IntegrationComponentProps> = ({
  apiKey,
}) => {
  return (
    <IntegrationTemplate
      apiKey={apiKey}
      integration="Functional Decorators"
      url={pythonLogoUrl}
      codeTitle={CODE_TITLE}
      code={CODE}
    />
  );
};

export default FunctionDecorators;
