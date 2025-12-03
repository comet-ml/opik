"""
Example script to test the EXPERIMENT_FINISHED alert event.

This script:
1. Creates a small dataset with 5 items
2. Defines a simple task and scoring metric
3. Runs an evaluation which triggers the experiment finished event
4. The event can be captured by alerts configured in the Opik UI

Prerequisites:
- Opik SDK installed: pip install opik
- Opik server running (local or cloud)
- Optional: Configure an alert in the UI for "experiment:finished" event type

Usage:
    # For local Opik (default on port 5173):
    python test_experiment_finished_alert.py
    
    # For local Opik on different port:
    OPIK_URL_OVERRIDE=http://localhost:5174/api python test_experiment_finished_alert.py
    
    # For cloud Opik:
    OPIK_API_KEY=your_api_key python test_experiment_finished_alert.py
"""
import os
import sys

from opik import Opik, track
from opik.evaluation import evaluate
from opik.evaluation.metrics import Equals

# Configure for local Opik instance
# You can override this by setting OPIK_URL_OVERRIDE environment variable
if "OPIK_URL_OVERRIDE" not in os.environ:
    os.environ["OPIK_URL_OVERRIDE"] = "http://localhost:8080"
    
if "OPIK_WORKSPACE" not in os.environ:
    os.environ["OPIK_WORKSPACE"] = "default"

# Initialize Opik client
try:
    client = Opik()
    print(f"✓ Connected to Opik at: {os.environ.get('OPIK_URL_OVERRIDE')}")
except Exception as e:
    print(f"✗ Failed to connect to Opik server")
    print(f"  Error: {str(e)}")
    print(f"\n  Make sure Opik is running at: {os.environ.get('OPIK_URL_OVERRIDE')}")
    print(f"  Or set OPIK_URL_OVERRIDE environment variable to your Opik URL")
    print(f"\n  Example:")
    print(f"    OPIK_URL_OVERRIDE=http://localhost:5174/api python {sys.argv[0]}")
    sys.exit(1)

print("=" * 60)
print("Testing EXPERIMENT_FINISHED Alert Event")
print("=" * 60)

# Step 1: Create a dataset with 5 items
print("\n1. Creating dataset with 5 items...")

dataset_name = "test-experiment-finished-dataset"

# Try to get existing dataset, or create new one
try:
    dataset = client.get_dataset(dataset_name)
    print(f"   ✓ Found existing dataset: {dataset_name}")
except Exception as e:
    print(f"   Dataset not found, creating new one...")
    try:
        dataset = client.create_dataset(
            name=dataset_name,
            description="Test dataset for experiment finished alert"
        )
        print(f"   ✓ Created new dataset: {dataset_name}")
    except Exception as create_error:
        print(f"\n✗ Failed to create dataset")
        print(f"  Error: {str(create_error)}")
        print(f"\n  This might mean:")
        print(f"  1. The Opik backend is not fully started")
        print(f"  2. The API endpoints are not accessible")
        print(f"  3. There's a database connection issue")
        print(f"\n  Please check that your Opik instance is running correctly.")
        print(f"  You can verify by visiting: {os.environ.get('OPIK_URL_OVERRIDE', '').replace('/api', '')}")
        sys.exit(1)

# Insert dataset items
dataset_items = [
    {"input": "What is 2+2?", "expected_output": "4"},
    {"input": "What is 3+3?", "expected_output": "6"},
    {"input": "What is 5+5?", "expected_output": "10"},
    {"input": "What is 7+7?", "expected_output": "14"},
    {"input": "What is 10+10?", "expected_output": "20"},
]

print(f"   ✓ Inserting {len(dataset_items)} items...")
dataset.insert(dataset_items)
print(f"   ✓ Dataset ready with {len(dataset_items)} items")

# Step 2: Define a simple task
print("\n2. Defining evaluation task...")


@track
def simple_math_task(item):
    """
    Simple task that extracts the numbers from the input and adds them.
    This simulates an LLM task without requiring an actual LLM.
    """
    # Extract numbers from input like "What is 2+2?"
    import re
    numbers = re.findall(r'\d+', item["input"])
    if len(numbers) == 2:
        result = str(int(numbers[0]) + int(numbers[1]))
    else:
        result = "unknown"
    
    return {
        "input": item["input"],
        "output": result,
        "expected_output": item["expected_output"]
    }


print("   ✓ Task defined: simple_math_task")

# Step 3: Define scoring metric
print("\n3. Defining scoring metric...")

# Use the built-in Equals metric
scoring_metric = Equals(name="exact_match")
print("   ✓ Metric defined: exact_match")

# Step 4: Run evaluation
print("\n4. Running evaluation...")
print("   This will:")
print("   - Create an experiment")
print("   - Run the task on all 5 dataset items")
print("   - Score the results")
print("   - Call finish_experiments() to trigger the alert event")
print()

result = evaluate(
    dataset=dataset,
    task=simple_math_task,
    scoring_metrics=[scoring_metric],
    experiment_name="Test Experiment - Finished Alert",
    verbose=1,  # Show progress
    scoring_key_mapping={
        "reference": "expected_output",  # Map expected_output to reference for the Equals metric
    }
)

# Step 5: Display results
print("\n" + "=" * 60)
print("Evaluation Complete!")
print("=" * 60)
print(f"\n✓ Experiment ID: {result.experiment_id}")
print(f"✓ Experiment Name: {result.experiment_name}")
print(f"✓ Total test results: {len(result.test_results)}")
print(f"✓ Experiment URL: {result.experiment_url}")

# Display score summary
if result.test_results:
    # Extract scores from test results - each result has a scoring_results dict
    try:
        scores = []
        for r in result.test_results:
            if hasattr(r, 'scoring_results') and r.scoring_results:
                for metric_name, metric_result in r.scoring_results.items():
                    if hasattr(metric_result, 'value') and metric_result.value is not None:
                        scores.append(metric_result.value)
        
        if scores:
            avg_score = sum(scores) / len(scores)
            print(f"✓ Average score: {avg_score:.2f}")
            print(f"✓ Passed: {sum(1 for s in scores if s > 0.5)}/{len(scores)} items")
    except Exception:
        # If score extraction fails, just skip this display
        pass

print("\n" + "=" * 60)
print("EXPERIMENT_FINISHED Event Triggered!")
print("=" * 60)
print("\nWhat happened:")
print("1. ✓ Experiment was created and ran successfully")
print("2. ✓ All data was flushed to the backend")
print("3. ✓ finish_experiments() was called with experiment ID")
print("4. ✓ Backend triggered EXPERIMENT_FINISHED alert event")
print("5. ✓ Any configured alerts should now be firing!")

print("\n" + "=" * 60)
print("Next Steps:")
print("=" * 60)
print("1. Go to the Opik UI: Settings > Alerts")
print("2. Create a new alert with event type: 'Experiment finished'")
print("3. Configure webhook/Slack/PagerDuty destination")
print("4. Run this script again to test the alert")
print("\nFor more info, visit: https://www.comet.com/docs/opik/")
print("=" * 60)

