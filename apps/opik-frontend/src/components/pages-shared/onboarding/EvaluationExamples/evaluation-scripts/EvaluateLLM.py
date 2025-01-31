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
        "context": "Python is known for its simplicity and readability.",
        "expected_output": "Python's key features include dynamic typing and automatic memory management."
    },
    {
        "input": "How does garbage collection work in Python?",
        "context": "Python uses reference counting and a cyclic garbage collector.",
        "expected_output": "Python uses reference counting for garbage collection."
    }
])

# Create the evaluation task
def evaluation_task(dataset_item):
    # Simulate RAG pipeline, replace this with your LLM application
    output = "<LLM response placeholder>"
    
    return {
        "output": output
    }

# Run the evaluation
result = evaluate(
    dataset=dataset,
    task=evaluation_task,
    scoring_metrics=[
        ContextPrecision(),
        ContextRecall()
    ],
    experiment_name="rag_evaluation"
)
