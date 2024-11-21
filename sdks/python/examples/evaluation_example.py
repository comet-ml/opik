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
        {"input": "Greet me!", "context": []},
        {"input": "Ok, I'm leaving, bye!", "context": []},
        {"input": "How are you doing?", "context": []},
        {"input": "Give a json example!", "context": []},
        {
            "input": "What is the main currency in european union?",
            "context": ["Euro is the main european currency. It is used across most EU countries"]
        }
    ]
"""

dataset.insert_from_json(json_array=json)


@track()
def llm_task(item: Dict[str, Any]) -> Dict[str, Any]:
    if "input" not in item:
        return {"output": "empty string"}
    else:
        response = openai_client.chat.completions.create(
            model="gpt-3.5-turbo",
            messages=[{"role": "user", "content": item.get("input")}],
        )

    return {"output": response.choices[0].message.content}


evaluate(
    experiment_name="My experiment",
    dataset=dataset,
    task=llm_task,
    scoring_metrics=[is_json, hallucination],
)
