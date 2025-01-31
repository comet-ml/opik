import opik
from opik.evaluation import evaluate
from opik.evaluation.metrics import ContextPrecision, ContextRecall

# INJECT_OPIK_CONFIGURATION

# Create a dataset with questions and their contexts
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("RAG evaluation dataset")
dataset.insert([
    {
        "input": "What are the key features of Python?",
        "context": "Python is known for its simplicity and readability. Key features include dynamic typing, automatic memory management, and an extensive standard library.",
        "expected_output": "Python's key features include dynamic typing, automatic memory management, and an extensive standard library."
    },
    {
        "input": "How does garbage collection work in Python?",
        "context": "Python uses reference counting and a cyclic garbage collector. When an object's reference count drops to zero, it is deallocated.",
        "expected_output": "Python uses reference counting for garbage collection. Objects are deallocated when their reference count reaches zero."
    }
])

def rag_task(item):
    # Simulate RAG pipeline
    output = "<LLM response placeholder>"

    return {
        "output": output
    }

# Run the evaluation
result = evaluate(
    dataset=dataset,
    task=rag_task,
    scoring_metrics=[
        ContextPrecision(),
        ContextRecall()
    ],
    experiment_name="rag_evaluation"
)