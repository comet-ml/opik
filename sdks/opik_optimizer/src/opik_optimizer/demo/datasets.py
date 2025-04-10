import opik
from typing import Literal, List, Dict, Any
from .. import utils

def get_or_create_dataset(name: Literal["hotpot-300", "halu-eval", "tiny-test"]) -> opik.Dataset:
    if name == "hotpot-300":
        return utils.get_or_create_dataset(
            dataset_name=name,
            description="HotpotQA dataset with 300 examples",
            data_loader=_load_hotpot_300
        )
    elif name == "halu-eval":
        return utils.get_or_create_dataset(
            dataset_name=name,
            description="HaluEval dataset with 300 examples",
            data_loader=_load_halu_eval
        )
    elif name == "tiny-test":
        return utils.get_or_create_dataset(
            dataset_name=name,
            description="Tiny test dataset with 5 examples",
            data_loader=_load_tiny_test
        )
    
    raise ValueError(f"Unknown example dataset name: {name}")


def _load_hotpot_300() -> List[Dict[str, Any]]:
    from dspy.datasets import HotPotQA

    seed = 42
    size = 300

    trainset = [
        x.with_inputs("question")
        for x in HotPotQA(train_seed=seed, train_size=size).train
    ]

    data = []
    for row in trainset:
        d = row.toDict()
        del d["dspy_uuid"]
        del d["dspy_split"]
        data.append(d)
    
    return data


def _load_halu_eval() -> List[Dict[str, Any]]:
    import pandas as pd

    df = pd.read_parquet(
        "hf://datasets/pminervini/HaluEval/general/data-00000-of-00001.parquet"
    )
    df = df.sample(n=300, random_state=42)

    dataset_records = [
        {
            "input": x["user_query"],
            "llm_output": x["chatgpt_response"],
            "expected_hallucination_label": x["hallucination"],
        }
        for x in df.to_dict(orient="records")
    ]

    return dataset_records


def _load_tiny_test() -> List[Dict[str, Any]]:
    return [
        {
            "text": "What is the capital of France?",
            "label": "Paris",
            "metadata": {
                "context": "France is a country in Europe. Its capital is Paris."
            }
        },
        {
            "text": "Who wrote Romeo and Juliet?",
            "label": "William Shakespeare",
            "metadata": {
                "context": "Romeo and Juliet is a famous play written by William Shakespeare."
            }
        },
        {
            "text": "What is 2 + 2?",
            "label": "4",
            "metadata": {
                "context": "Basic arithmetic: 2 + 2 equals 4."
            }
        },
        {
            "text": "What is the largest planet in our solar system?",
            "label": "Jupiter",
            "metadata": {
                "context": "Jupiter is the largest planet in our solar system."
            }
        },
        {
            "text": "Who painted the Mona Lisa?",
            "label": "Leonardo da Vinci",
            "metadata": {
                "context": "The Mona Lisa was painted by Leonardo da Vinci."
            }
        }
    ] 