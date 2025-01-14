import React from "react";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";

const CODE = `import getpass
import os

import dspy
from opik.integrations.dspy.callback import OpikCallback
${OPIK_API_KEY_TEMPLATE}
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")


project_name = "DSPY"

lm = dspy.LM(
    model="openai/gpt-4o-mini",
)
dspy.configure(lm=lm)


opik_callback = OpikCallback(project_name=project_name)
dspy.settings.configure(
    callbacks=[opik_callback],
)

cot = dspy.ChainOfThought("question -> answer")
print(cot(question="What is the meaning of life?"))`;

const Groq: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return <IntegrationTemplate apiKey={apiKey} code={CODE} />;
};

export default Groq;
