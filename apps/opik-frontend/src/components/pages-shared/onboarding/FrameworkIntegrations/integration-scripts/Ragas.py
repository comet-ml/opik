from langchain_openai.chat_models import ChatOpenAI
from langchain_openai.embeddings import OpenAIEmbeddings
from opik import configure  # HIGHLIGHTED_LINE
from opik.evaluation.metrics import RagasMetricWrapper  # HIGHLIGHTED_LINE
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import AnswerRelevancy

configure()  # HIGHLIGHTED_LINE

llm = LangchainLLMWrapper(ChatOpenAI())
emb = LangchainEmbeddingsWrapper(OpenAIEmbeddings())
ragas_answer_relevancy_metric = AnswerRelevancy(llm=llm, embeddings=emb)

answer_relevancy_metric = RagasMetricWrapper(  # HIGHLIGHTED_LINE
    ragas_answer_relevancy_metric, track=True  # HIGHLIGHTED_LINE
)  # HIGHLIGHTED_LINE

row = {
    "user_input": "What is the capital of France?",
    "response": "Paris",
    "retrieved_contexts": ["Paris is the capital of France.", "Paris is in France."],
}
score = answer_relevancy_metric.score(**row)
print("Answer Relevancy score:", score)
