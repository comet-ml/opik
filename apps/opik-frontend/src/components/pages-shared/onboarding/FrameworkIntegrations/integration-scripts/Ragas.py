import asyncio, os
from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from ragas.dataset_schema import SingleTurnSample
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.integrations.opik import OpikTracer
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import AnswerRelevancy

# INJECT_OPIK_CONFIGURATION

llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())
answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)


def compute_metric(metric, row):
    row = SingleTurnSample(**row)
    opik_tracer = OpikTracer(tags=["ragas"])

    async def get_score(opik_tracer, metric, row):
        return await metric.single_turn_ascore(row, callbacks=[opik_tracer])

    loop = asyncio.get_event_loop()
    return loop.run_until_complete(get_score(opik_tracer, metric, row))


row = {
    "user_input": "What is the capital of France?",
    "response": "Paris",
    "retrieved_contexts": ["Paris is the capital of France.", "Paris is in France."],
}
score = compute_metric(answer_relevancy_metric, row)
print("Answer Relevancy score:", score)
