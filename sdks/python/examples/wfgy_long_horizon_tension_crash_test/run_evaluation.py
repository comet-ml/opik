import os
from typing import Any, Dict

import opik
from opik.evaluation import evaluate
from opik.evaluation.metrics import Hallucination
from opik.integrations.openai import track_openai
from openai import OpenAI


# ============================================================
# 1) Basic settings (override with env vars if you like)
# ============================================================

DATASET_NAME = os.getenv(
    "WFGY_DATASET_NAME",
    "wfgy_long_horizon_tension_crash_test",
)

PROJECT_NAME = os.getenv(
    "OPIK_PROJECT_NAME",
    "wfgy_long_horizon_tension_crash_test",
)

EXPERIMENT_NAME = os.getenv(
    "WFGY_EXPERIMENT_NAME",
    "wfgy_long_horizon_tension_crash_test_gpt4o",
)

MODEL_NAME = os.getenv(
    "WFGY_MODEL_NAME",
    "gpt-4o-mini",
)

TEMPERATURE = float(os.getenv("WFGY_TEMPERATURE", "0.2"))

EXPERIMENT_SETTINGS: Dict[str, Any] = {
    "model": MODEL_NAME,
    "temperature": TEMPERATURE,
    "description": "WFGY long-horizon tension crash test",
}


# ============================================================
# 2) Prepare tracked OpenAI client
#    Make sure OPENAI_API_KEY is set in the environment.
# ============================================================

openai_client = track_openai(OpenAI())


# ============================================================
# 3) Define a Prompt object so Opik can link it to the experiment
# ============================================================

WFGY_PROMPT = opik.Prompt(
    name="wfgy_long_horizon_tension_crash_test_v1",
    prompt=(
        "You are an advanced reasoning assistant.\n\n"
        "You will receive one long-horizon test case as the user input.\n"
        "Each test case comes from the WFGY tension crash test suite.\n\n"
        "Rules:\n"
        "1. Read the entire input carefully. Do not skip constraints.\n"
        "2. Think step by step and make the reasoning explicit.\n"
        "3. If the task asks for a plan, return a numbered list of concrete steps.\n"
        "4. If the task is ambiguous, explain the ambiguity first,\n"
        "   then propose at least two possible resolutions.\n"
        "5. Finish with a short paragraph that summarises what changed\n"
        "   between the start and the end of the scenario.\n"
    ),
)


# ============================================================
# 4) Wrap the model call in a tracked function
# ============================================================

@opik.track
def wfgy_long_horizon_app(input_text: str) -> Dict[str, Any]:
    """
    Single evaluation step for one dataset item.
    This is the function you would normally use inside your application.
    """

    messages = [
        {
            "role": "system",
            "content": WFGY_PROMPT.prompt,
        },
        {
            "role": "user",
            "content": input_text,
        },
    ]

    response = openai_client.chat.completions.create(
        model=MODEL_NAME,
        messages=messages,
        temperature=TEMPERATURE,
    )

    output_text = response.choices[0].message.content or ""

    # The keys of this dict are merged with the dataset item
    # before scoring, so keep them simple and flat.
    return {"output": output_text}


def evaluation_task(dataset_item: Dict[str, Any]) -> Dict[str, Any]:
    """
    Adapter from dataset item to the model call.
    Opik will pass each dataset item here during evaluation.
    """

    # We expect each dataset item to have an "input" field.
    return wfgy_long_horizon_app(dataset_item["input"])


# ============================================================
# 5) Run the evaluation with Opik
# ============================================================

def main() -> None:
    # Opik will read OPIK_API_KEY, OPIK_WORKSPACE and OPIK_URL_OVERRIDE
    # from the environment. Do not hard-code them here.
    client = opik.Opik()

    dataset = client.get_or_create_dataset(name=DATASET_NAME)

    hallucination_metric = Hallucination()

    print(f"Using dataset     : {dataset.name}")
    print(f"Project name      : {PROJECT_NAME}")
    print(f"Experiment name   : {EXPERIMENT_NAME}")
    print(f"Model             : {MODEL_NAME}")
    print(f"Temperature       : {TEMPERATURE}")

    evaluation = evaluate(
        dataset=dataset,
        task=evaluation_task,
        scoring_metrics=[hallucination_metric],
        # Use a shallow copy to avoid any accidental mutation of EXPERIMENT_SETTINGS
        experiment_config={**EXPERIMENT_SETTINGS},
        project_name=PROJECT_NAME,
        experiment_name=EXPERIMENT_NAME,
        prompts=[WFGY_PROMPT],
    )

    print("\nEvaluation finished.")
    print(f"Experiment id   : {evaluation.experiment_id}")
    print("Open the Opik UI and go to Experiments to inspect traces and scores.")


if __name__ == "__main__":
    main()
