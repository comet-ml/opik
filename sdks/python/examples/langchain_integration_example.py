from langchain_community.llms import fake
from langchain.prompts import PromptTemplate
from opik.integrations.langchain.opik_tracer import OpikTracer


# @opik.track(capture_input=False)
def f(test_prompts, chain, callback):
    result = chain.invoke(input=test_prompts, config={"callbacks": [callback]})
    return result


llm = fake.FakeListLLM(
    responses=["I'm sorry, I don't think I'm talented enough to write a synopsis"]
)
template = "Given the title of play, write a synopsys for that. Title: {title}."
prompt_template = PromptTemplate(input_variables=["title"], template=template)
synopsis_chain = prompt_template | llm
callback = OpikTracer(tags=["tag1", "tag2"], metadata={"a": "b"})

test_prompts = {"title": "Documentary about Bigfoot in Paris"}

print(f(test_prompts, synopsis_chain, callback))

callback.flush()
