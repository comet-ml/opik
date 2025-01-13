import React from "react";
import { FrameworkIntegrationComponentProps } from "@/components/pages-shared/onboarding/FrameworkIntegrations/types";
import IntegrationTemplate, {
  OPIK_API_KEY_TEMPLATE,
} from "@/components/pages-shared/onboarding/FrameworkIntegrations/integrations/IntegrationTemplate";

const CODE_TITLE =
  "You can use the `OpikTracer` provided by the Opik SDK to log all LangChains calls to Opik:";

const CODE = `import getpass
import os

from langchain.prompts import PromptTemplate
from langchain_openai import OpenAI
from opik.integrations.langchain import OpikTracer
${OPIK_API_KEY_TEMPLATE}
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

# Initialize the tracer
opik_tracer = OpikTracer()

# Create the LLM Chain using LangChain
llm = OpenAI(temperature=0, callbacks=[opik_tracer])

prompt_template = PromptTemplate(
    input_variables=["input"],
    template="Translate the following text to French: {input}",
)

# Use pipe operator to create LLM chain
llm_chain = prompt_template | llm

# Generate the translations
print(llm_chain.invoke({"input": "Hello, how are you?"}, callbacks=[opik_tracer]))`;

const LangChain: React.FC<FrameworkIntegrationComponentProps> = ({
  apiKey,
}) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default LangChain;
