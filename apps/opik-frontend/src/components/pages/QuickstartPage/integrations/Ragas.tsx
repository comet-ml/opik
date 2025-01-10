import React from "react";
import IntegrationTemplate from "@/components/pages/QuickstartPage/integrations/IntegrationTemplate";
import { FrameworkIntegrationComponentProps } from "@/components/shared/FrameworkIntegrations/types";

const CODE_TITLE =
  "You can use the `OpikTracer` provided as part of the Ragas integration to log all Ragas scores to Opik:";

const CODE = `import asyncio

# Import the metric
from ragas.metrics import AnswerRelevancy

# Import some additional dependencies
from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.integrations.opik import OpikTracer
from ragas.llms import LangchainLLMWrapper

# Initialize the Ragas metric
llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())
answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)

# Call Ragas metric logging with the Opik Tracer
answer_relevancy_metric.single_turn_ascore(row, callbacks=[OpikTracer()])
`;

const Ragas: React.FC<FrameworkIntegrationComponentProps> = ({ apiKey }) => {
  return (
    <IntegrationTemplate apiKey={apiKey} codeTitle={CODE_TITLE} code={CODE} />
  );
};

export default Ragas;
