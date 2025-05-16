import os
import getpass
import subprocess
import json
import opik
import re
from typing import Union, Optional
from opik_optimizer import (
    EvolutionaryOptimizer,
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik.evaluation.metrics import LevenshteinRatio

# --- Configuration ---
# Ensure API keys are set
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

# Opik Project Configuration
OPIK_PROJECT_NAME = "opik-tinyqab-evolutionary-example"
OPTIMIZER_MODEL = "openai/gpt-4o-mini" # Model for Opik Optimizer
GENERATOR_MODEL = "openai/gpt-4o-mini" # Model for tinyqabenchmarkpp

# Demo project configuration
DEMO_PROJECT_NAME = "Demo chatbot 🤖"  # Demo chatbot project name

# tinyqabenchmarkpp Configuration
TQB_NUM_QUESTIONS = 20  # Number of questions to generate
TQB_LANGUAGES = "en"
TQB_CATEGORIES = "use context provided and elaborate on it to generate a more detailed answers"
TQB_DIFFICULTY = "medium"

# EvolutionaryOptimizer Configuration
POPULATION_SIZE = 5 # Reduced for quicker demo
NUM_GENERATIONS = 2 # Reduced for quicker demo
N_SAMPLES_OPTIMIZATION = 10 # Number of samples from dataset for optimization eval

def clean_text(text: str) -> str:
    """
    Cleans text by removing special characters and normalizing whitespace.
    """
    if not text:
        return ""
    # Replace special characters with spaces, but keep basic punctuation
    text = re.sub(r'[^\w\s.,!?;:\'"-]', ' ', text)
    # Normalize whitespace
    text = re.sub(r'\s+', ' ', text)
    # Remove any leading/trailing whitespace
    text = text.strip()
    return text

def extract_text_from_dict(d: dict) -> list[str]:
    """
    Recursively extracts text from a dictionary.
    """
    texts = []
    for key, value in d.items():
        if isinstance(value, str):
            texts.append(value)
        elif isinstance(value, dict):
            texts.extend(extract_text_from_dict(value))
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, str):
                    texts.append(item)
                elif isinstance(item, dict):
                    texts.extend(extract_text_from_dict(item))
    return texts

def get_demo_project_traces() -> str:
    """
    Fetches traces from the demo project and formats them as a context string.
    Returns a formatted string with explanatory text and cleaned traces.
    """
    print("Fetching traces from demo project...")
    client = opik.Opik()
    
    try:
        # Search for traces in the project directly using project name
        traces = client.search_traces(
            project_name=DEMO_PROJECT_NAME,
            max_results=1000  # Get more traces
        )
        
        if not traces:
            raise ValueError(f"No traces found in project '{DEMO_PROJECT_NAME}'")
        
        print(f"Found {len(traces)} traces")
        
        # Extract and clean text from traces
        cleaned_texts = []
        for i, trace in enumerate(traces):
            # Extract from input
            if trace.input:
                if isinstance(trace.input, dict):
                    texts = extract_text_from_dict(trace.input)
                    for text in texts:
                        cleaned = clean_text(text)
                        if cleaned:
                            cleaned_texts.append(cleaned)
                elif isinstance(trace.input, str):
                    cleaned = clean_text(trace.input)
                    if cleaned:
                        cleaned_texts.append(cleaned)
            
            # Extract from output
            if trace.output:
                if isinstance(trace.output, dict):
                    texts = extract_text_from_dict(trace.output)
                    for text in texts:
                        cleaned = clean_text(text)
                        if cleaned:
                            cleaned_texts.append(cleaned)
                elif isinstance(trace.output, str):
                    cleaned = clean_text(trace.output)
                    if cleaned:
                        cleaned_texts.append(cleaned)
            
            # Extract from metadata if it exists
            if trace.metadata:
                if isinstance(trace.metadata, dict):
                    texts = extract_text_from_dict(trace.metadata)
                    for text in texts:
                        cleaned = clean_text(text)
                        if cleaned:
                            cleaned_texts.append(cleaned)
        
        if not cleaned_texts:
            print("Debug: No text content found in traces. Here's what we got:")
            for i, trace in enumerate(traces[:5]):  # Show first 5 traces for debugging
                print(f"\nTrace {i}:")
                print(f"Input: {trace.input}")
                print(f"Output: {trace.output}")
                print(f"Metadata: {trace.metadata}")
            raise ValueError("No valid text content found in traces")
        
        # Remove duplicates while preserving order
        seen = set()
        unique_texts = []
        for text in cleaned_texts:
            if text not in seen:
                seen.add(text)
                unique_texts.append(text)
        
        # Create the context string with explanatory text
        context = f"""This is a collection of conversation traces from the Opik demo chatbot project. 
The following text contains various interactions and responses that can be used to generate relevant questions and answers.

{chr(10).join(unique_texts)}"""
        
        print(f"Found and cleaned {len(unique_texts)} unique text segments from traces")
        print(f"Total context length: {len(context)} characters")
        return context
        
    except Exception as e:
        print(f"Error fetching or processing traces: {e}")
        raise

def configure_opik():
    """Configures Opik for the session."""
    try:
        opik.configure() # Will prompt for API key if not set
        print("Opik configured successfully.")
    except Exception as e:
        print(f"Error configuring Opik: {e}")
        print("Please ensure your Comet API key is correctly set up.")
        raise

def generate_synthetic_data():
    """
    Generates synthetic QA data using tinyqabenchmarkpp.
    Returns the generated data as a string.
    """
    print(f"Generating {TQB_NUM_QUESTIONS} synthetic QA pairs using tinyqabenchmarkpp...")
    
    # Get context from demo project traces
    try:
        context = get_demo_project_traces()
    except Exception as e:
        print(f"Failed to get context from demo project: {e}")
        return None
    
    command = [
        "python", "-m", "tinyqabenchmarkpp.generate",
        "--num", str(TQB_NUM_QUESTIONS),
        "--languages", TQB_LANGUAGES,
        "--categories", TQB_CATEGORIES,
        "--difficulty", TQB_DIFFICULTY,
        "--model", GENERATOR_MODEL,
        "--str-output",
        "--context", context
    ]
    
    try:
        process = subprocess.run(command, capture_output=True, text=True, check=True)
        print("tinyqabenchmarkpp output:")
        print(process.stdout)
        if process.stderr:
            print("tinyqabenchmarkpp errors:")
            print(process.stderr)
        print("Synthetic data generated successfully")
        return process.stdout
    except subprocess.CalledProcessError as e:
        print(f"Error running tinyqabenchmarkpp generator: {e}")
        print("stdout:", e.stdout)
        print("stderr:", e.stderr)
        return None
    except FileNotFoundError:
        print("Error: 'python -m tinyqabenchmarkpp.generate' command not found.")
        print("Please ensure tinyqabenchmarkpp is installed and accessible in your Python environment.")
        print("You can install it using: pip install tinyqabenchmarkpp")
        return None

def load_synthetic_data_to_opik(data_str: str) -> Optional[opik.Dataset]:
    """
    Loads the generated JSONL data into an Opik Dataset.
    """
    print("Loading synthetic data into Opik Dataset...")
    items = []
    try:
        for line_num, line in enumerate(data_str.strip().split('\n')):
            try:
                data = json.loads(line.strip())
                if not isinstance(data, dict):
                    print(f"Skipping non-dictionary item in JSONL line {line_num + 1}: {line.strip()}")
                    continue
                item = {
                    "question": data.get("text"),
                    "answer": data.get("label"),
                    "generated_context": data.get("context"),
                    "category": data.get("tags", {}).get("category"),
                    "difficulty": data.get("tags", {}).get("difficulty")
                }
                if not item["question"] or not item["answer"]:
                    print(f"Skipping item in JSONL line {line_num + 1} due to missing question or answer: {data}")
                    continue
                items.append(item)
            except json.JSONDecodeError:
                print(f"Skipping invalid JSON line {line_num + 1}: {line.strip()}")
    except Exception as e:
        print(f"An unexpected error occurred while processing data: {e}")
        return None

    if not items:
        print("No valid items found in the generated data.")
        return None

    print(f"Loaded {len(items)} items.")
    
    # Sanitize dataset name for Opik
    dataset_name = f"tinyqab-dataset-{TQB_CATEGORIES.replace(',', '_')}-{TQB_NUM_QUESTIONS}"
    dataset_name = "".join(c if c.isalnum() or c in ['-', '_'] else '_' for c in dataset_name)
    
    try:
        # Create a new dataset using opik.Opik().create_dataset()
        opik_client = opik.Opik()
        dataset = opik_client.create_dataset(
            name=dataset_name,
            description=f"Synthetic QA from tinyqabenchmarkpp for {TQB_CATEGORIES}"
        )
        
        # Add items to the dataset
        dataset.insert(items)
            
        print(f"Opik Dataset '{dataset.name}' created successfully with ID: {dataset.id}")
        return dataset
    except Exception as e:
        print(f"Error creating Opik Dataset: {e}")
        return None

def main():
    """
    Main function to run the data generation and optimization pipeline.
    """
    print("Starting Opik and tinyqabenchmarkpp integration script...")
    print("Please ensure you have the following packages installed:")
    print("  pip install opik-optimizer tinyqabenchmarkpp litellm openai")
    print("-" * 30)

    configure_opik()

    generated_data = generate_synthetic_data()
    if not generated_data:
        print("Failed to generate synthetic data. Exiting.")
        return

    opik_synthetic_dataset = load_synthetic_data_to_opik(generated_data)
    if not opik_synthetic_dataset:
        print("Failed to load synthetic data into Opik. Exiting.")
        return

    print("-" * 30)
    print("Proceeding with prompt optimization using EvolutionaryOptimizer...")

    # Initial prompt for the optimizer
    initial_prompt = "Be a helpful assistant."

    # Metric Configuration
    metric_config = MetricConfig(
        metric=LevenshteinRatio(project_name=OPIK_PROJECT_NAME),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        },
    )

    # Task Configuration
    task_config = TaskConfig(
        instruction_prompt=initial_prompt,
        input_dataset_fields=["question", "generated_context"],
        output_dataset_field="answer",
    )

    # Initialize the optimizer
    optimizer = EvolutionaryOptimizer(
        model=OPTIMIZER_MODEL,
        project_name=OPIK_PROJECT_NAME,
        population_size=POPULATION_SIZE,
        num_generations=NUM_GENERATIONS,
        enable_moo=False,
        enable_llm_crossover=True,
        infer_output_style=True,
        verbose=1,
    )

    print(f"Optimizing prompt using {OPTIMIZER_MODEL} on Opik project '{OPIK_PROJECT_NAME}'...")
    print(f"Optimizer will run for {NUM_GENERATIONS} generations with a population of {POPULATION_SIZE}.")
    print(f"Each evaluation will use {N_SAMPLES_OPTIMIZATION} samples from the dataset '{opik_synthetic_dataset.name}'.")

    # Optimize the prompt
    try:
        result = optimizer.optimize_prompt(
            dataset=opik_synthetic_dataset,
            metric_config=metric_config,
            task_config=task_config,
            n_samples=N_SAMPLES_OPTIMIZATION,
        )

        print("-" * 30)
        print("Optimization complete.")
        result.display()

    except Exception as e:
        print(f"An error occurred during prompt optimization: {e}")
        import traceback
        traceback.print_exc()

    print("-" * 30)
    print("Script finished.")

if __name__ == "__main__":
    main() 