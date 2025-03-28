# Using Opik with LangGraph

This notebook showcases how to use Opik with LangGraph. [LangGraph](https://langchain-ai.github.io/langgraph/) is a library for building stateful, multi-actor applications with LLMs, used to create agent and multi-agent workflows

In this notebook, we will create a simple LangGraph workflow and focus on how to track it's execution with Opik. To learn more about LangGraph, check out the [official documentation](https://langchain-ai.github.io/langgraph/).

## Creating an account on Opik Cloud

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=langgraph&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&=opik&utm_medium=colab&utm_content=langgraph&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=langgraph&utm_campaign=opik) for more information.


```python
%pip install --quiet -U langchain langgraph opik
```


```python
import opik

opik.configure(use_local=False)
```

## Create the LangGraph graph

The LangGraph graph we will be created in made up of 3 nodes:

1. `classify_input`: Classify the input question
2. `handle_greeting`: Handle the greeting question
3. `handle_search`: Handle the search question

*Note*: We will not be using any LLM calls or tools in this example to keep things simple. However in most cases, you will want to use tools to interact with external systems.


```python
# We will start by creating simple functions to classify the input question and handle the greeting and search questions.
def classify(question: str) -> str:
    return "greeting" if question.startswith("Hello") else "search"


def classify_input_node(state):
    question = state.get("question", "").strip()
    classification = classify(question)  # Assume a function that classifies the input
    return {"classification": classification}


def handle_greeting_node(state):
    return {"response": "Hello! How can I help you today?"}


def handle_search_node(state):
    question = state.get("question", "").strip()
    search_result = f"Search result for '{question}'"
    return {"response": search_result}
```


```python
from langgraph.graph import StateGraph, END

from typing import TypedDict, Optional


class GraphState(TypedDict):
    question: Optional[str] = None
    classification: Optional[str] = None
    response: Optional[str] = None


workflow = StateGraph(GraphState)
workflow.add_node("classify_input", classify_input_node)
workflow.add_node("handle_greeting", handle_greeting_node)
workflow.add_node("handle_search", handle_search_node)


def decide_next_node(state):
    return (
        "handle_greeting"
        if state.get("classification") == "greeting"
        else "handle_search"
    )


workflow.add_conditional_edges(
    "classify_input",
    decide_next_node,
    {"handle_greeting": "handle_greeting", "handle_search": "handle_search"},
)

workflow.set_entry_point("classify_input")
workflow.add_edge("handle_greeting", END)
workflow.add_edge("handle_search", END)

app = workflow.compile()

# Display the graph
try:
    from IPython.display import Image, display

    display(Image(app.get_graph().draw_mermaid_png()))
except Exception:
    # This requires some extra dependencies and is optional
    pass
```

## Calling the graph with Opik tracing enabled

In order to log the execution of the graph, we need to define the OpikTracer callback:


```python
from opik.integrations.langchain import OpikTracer

tracer = OpikTracer(graph=app.get_graph(xray=True))
inputs = {"question": "Hello, how are you?"}
result = app.invoke(inputs, config={"callbacks": [tracer]})
print(result)
```

The graph execution is now logged on the Opik platform and can be viewed in the UI:

![LangGraph screenshot](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/langgraph_cookbook.png)


