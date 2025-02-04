import os
from langchain.prompts import PromptTemplate
from langchain_openai import OpenAI
from opik.integrations.langchain import OpikTracer # HIGHLIGHTED_LINE

# INJECT_OPIK_CONFIGURATION

opik_tracer = OpikTracer() # HIGHLIGHTED_LINE
llm = OpenAI(temperature=0, callbacks=[opik_tracer]) # HIGHLIGHTED_LINE
prompt_template = PromptTemplate(
    input_variables=["input"], template="Write a haiku about {input}"
)
llm_chain = prompt_template | llm
print(llm_chain.invoke({"input": "AI engineering"}, callbacks=[opik_tracer]))
