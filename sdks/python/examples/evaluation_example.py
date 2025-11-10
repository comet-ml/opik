from typing import Dict, Any

from opik.evaluation.metrics import IsJson, Hallucination
from opik.evaluation import evaluate
from opik import Opik, track
from opik.integrations.openai import track_openai
import openai


# os.environ["OPENAI_ORG_ID"] = "<>"
# os.environ["OPENAI_API_KEY"] = "<>"

openai_client = track_openai(openai.OpenAI())

is_json = IsJson()
hallucination = Hallucination()

client = Opik()
dataset = client.get_or_create_dataset(
    name="My 42 dataset", description="For storing stuff"
)

json = """
    [
        {
            "Model inputs": {"message": "Greet me!", "context": []}
        },
        {
            "Model inputs": {"message": "Ok, I'm leaving, bye!", "context": []}
        },
        {
            "Model inputs": {"message": "How are you doing?", "context": []}
        },
        {
            "Model inputs": {"message": "Give a json example!", "context": []}
        },
        {
            "Model inputs": {
                "message": "What is the main currency in european union?",
                "context": ["Euro is the main european currency. It is used across most EU countries"]
            }
        }
    ]
"""

dataset.insert_from_json(json_array=json, keys_mapping={"Model inputs": "input"})


@track()
def llm_task(item: Dict[str, Any]) -> Dict[str, Any]:
    response = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[{"role": "user", "content": item["input"]["message"]}],
    )

    return {
        "output": response.choices[0].message.content,
        "reference": "test",
    }


results = evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=llm_task,
    nb_samples=2,
    scoring_metrics=[is_json, hallucination],
)

print(results)
