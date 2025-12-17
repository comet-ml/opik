langchain
=========

Opik integrates with Langchain to allow you to log your Langchain calls to the Opik platform, simply wrap the Langchain client with `OpikTracer` to start logging::

   from langchain.chains import LLMChain
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
   print(translation)

You can learn more about the LangChain integration functions in the following sections:

.. toctree::
   :maxdepth: 4
   :titlesonly:

   OpikTracer
   track_langgraph
   extract_current_langgraph_span_data
