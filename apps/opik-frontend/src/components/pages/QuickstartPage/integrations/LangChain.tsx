import React from "react";
import { IntegrationComponentProps } from "@/components/pages/QuickstartPage/integrations/types";
import langChainLogoUrl from "/images/integrations/langchain.png";
import IntegrationTemplate from "@/components/pages/QuickstartPage/integrations/IntegrationTemplate";

const CODE_TITLE =
  "You can use the `OpikTracer` provided by the Opik SDK to log all LangChains calls to Opik:";

const CODE = `from langchain.chains import LLMChain
from langchain_openai import OpenAI
from langchain.prompts import PromptTemplate
from opik.integrations.langchain import OpikTracer

# Initialize the tracer
opik_tracer = OpikTracer()

# Create the LLM Chain using LangChain
llm = OpenAI(temperature=0)

prompt_template = PromptTemplate(
    input_variables=["input"],
    template="Translate the following text to French: {input}"
)

llm_chain = LLMChain(llm=llm, prompt=prompt_template)

# Generate the translations
translation = llm_chain.run("Hello, how are you?", callbacks=[opik_tracer])
print(translation)`;

const LangChain: React.FC<IntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate
      apiKey={apiKey}
      integration="LanfChain"
      url={langChainLogoUrl}
      codeTitle={CODE_TITLE}
      code={CODE}
    />
  );
};

export default LangChain;
