from opik_optimizer import MetaPromptOptimizer
from opik.evaluation.metrics import LevenshteinRatio
import os
import opik
from opik.api_objects.opik_client import Opik
from opik.api_objects.dataset.dataset_item import DatasetItem
from opik_optimizer.utils import TEST_DATASET_NAME
from opik_optimizer.datasets.test_data import TEST_DATA

# Initialize opik client
client = Opik()

# Create a tiny test dataset
try:
    # Try to get existing dataset
    opik_dataset = client.get_dataset(TEST_DATASET_NAME)
    # If dataset exists but has no data, delete it
    if not opik_dataset.get_items():
        print("Dataset exists but is empty - deleting it...")
        # Delete all items in the dataset
        items = opik_dataset.get_items()
        if items:
            opik_dataset.delete(items_ids=[item.id for item in items])
        # Delete the dataset itself
        client.delete_dataset(TEST_DATASET_NAME)
        raise Exception("Dataset deleted, will create new one")
except Exception:
    # Create new dataset
    print("Creating new dataset...")
    opik_dataset = client.create_dataset(
        name=TEST_DATASET_NAME,
        description="Tiny test dataset for prompt optimization"
    )
    
    # Convert test data to DatasetItem objects
    dataset_items = [
        DatasetItem(
            text=item["text"],
            label=item["label"],
            metadata=item["metadata"]
        ) for item in TEST_DATA
    ]
    
    # Insert the test data
    opik_dataset.__internal_api__insert_items_as_dataclasses__(dataset_items)
    
    # Verify data was added
    if not opik_dataset.get_items():
        raise Exception("Failed to add data to dataset")

# Initialize the optimizer with custom parameters
optimizer = MetaPromptOptimizer(
    model="o3-mini",  # Using o3-mini for evaluation
    reasoning_model="o3-mini",  # Using o3-mini for prompt generation
    max_rounds=3,  # Increased rounds for more optimization
    num_prompts_per_round=4,  # More prompts per round
    improvement_threshold=0.01,  # Lower threshold to allow more improvements
)

# Initial prompt to optimize - intentionally vague to allow for improvement
initial_prompt = """Answer the question."""

# Optimize the prompt
result = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric=LevenshteinRatio(),
    prompt=initial_prompt,
    input="text",
    output="label",
)

# Print results in a structured way
print("\nOptimization Results:")
print(f"Initial prompt: {result.initial_prompt}")
print(f"Initial score: {result.initial_score:.4f}")
print(f"Final prompt: {result.final_prompt}")
print(f"Final score: {result.final_score:.4f}")
print(f"Total rounds: {result.total_rounds}")
print(f"Stopped early: {result.stopped_early}")

print("\nRound-by-round details:")
for round_data in result.rounds:
    print(f"\nRound {round_data.round_number}:")
    print(f"  Current prompt: {round_data.current_prompt}")
    print(f"  Current score: {round_data.current_score:.4f}")
    print(f"  Best prompt: {round_data.best_prompt}")
    print(f"  Best score: {round_data.best_score:.4f}")
    print(f"  Improvement: {round_data.improvement:.2%}")
    print("\n  Generated prompts:")
    for prompt in round_data.generated_prompts:
        print(f"    - Score: {prompt['score']:.4f}")
        print(f"      Prompt: {prompt['prompt']}")
        print(f"      Improvement: {prompt['improvement']:.2%}") 