from typing import Optional, TypedDict

from langgraph.graph import END, StateGraph
from opik import configure  # HIGHLIGHTED_LINE
from opik.integrations.langchain import OpikTracer  # HIGHLIGHTED_LINE

configure()  # HIGHLIGHTED_LINE


def classify(question: str) -> str:
    return "greeting" if question.startswith("Hello") else "search"


def classify_input_node(state):
    question = state.get("question", "").strip()
    classification = classify(question)
    return {"classification": classification}


def handle_greeting_node(state):
    return {"response": "Hello! How can I help you today?"}


def handle_search_node(state):
    question = state.get("question", "").strip()
    search_result = f"Search result for '{question}'"
    return {"response": search_result}


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
tracer = OpikTracer(graph=app.get_graph(xray=True))  # HIGHLIGHTED_LINE
inputs = {"question": "Hello, how are you?"}
result = app.invoke(
    inputs,
    config={
        "callbacks": [tracer],  # HIGHLIGHTED_LINE
    },
)
print(result)
