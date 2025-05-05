import opik
from typing import Literal, List, Dict, Any
from .. import utils
from datasets import load_dataset
import traceback


class HaltError(Exception):
    """Exception raised when we need to halt the process due to a critical error."""

    pass


def get_or_create_dataset(
    name: Literal[
        "hotpot-300",
        "hotpot-500",
        "halu-eval-300",
        "tiny-test",
        "gsm8k",
        "hotpot_qa",
        "ai2_arc",
        "truthful_qa",
        "cnn_dailymail",
    ],
) -> opik.Dataset:
    """Get or create a dataset from HuggingFace."""
    try:
        # Try to get existing dataset first
        opik_client = opik.Opik()
        try:
            dataset = opik_client.get_dataset(name)
            if (
                dataset and len(dataset.get_items()) > 0
            ):  # Check if dataset exists and has data
                return dataset
            # If dataset exists but is empty, delete it
            print(f"Dataset {name} exists but is empty - deleting it...")
            opik_client.delete_dataset(name)
        except Exception:
            # If dataset doesn't exist, we'll create it
            pass

        # Load data based on dataset name
        if name == "hotpot-300":
            data = _load_hotpot_300()
        elif name == "hotpot-500":
            data = _load_hotpot_500()
        elif name == "halu-eval-300":
            data = _load_halu_eval_300()
        elif name == "tiny-test":
            data = _load_tiny_test()
        elif name == "gsm8k":
            data = _load_gsm8k()
        elif name == "hotpot_qa":
            data = _load_hotpot_qa()
        elif name == "ai2_arc":
            data = _load_ai2_arc()
        elif name == "truthful_qa":
            data = _load_truthful_qa()
        elif name == "cnn_dailymail":
            data = _load_cnn_dailymail()
        elif name == "math-50":
            data = _load_math_50()
        else:
            raise HaltError(f"Unknown dataset: {name}")

        if not data:
            raise HaltError(f"No data loaded for dataset: {name}")

        # Create dataset in Opik
        try:
            dataset = opik_client.create_dataset(name)  # Only pass name parameter
        except opik.rest_api.core.api_error.ApiError as e:
            if e.status_code == 409:  # Dataset already exists
                # Try to get the dataset again
                dataset = opik_client.get_dataset(name)
                if not dataset or len(dataset.get_items()) == 0:
                    raise HaltError(f"Dataset {name} exists but is empty")
                return dataset
            raise HaltError(f"Failed to create dataset {name}: {e}")

        # Insert data into the dataset
        try:
            dataset.insert(data)
        except Exception as e:
            raise HaltError(f"Failed to insert data into dataset {name}: {e}")

        # Verify data was added
        if not dataset.get_items():
            raise HaltError(f"Failed to add data to dataset {name}")

        return dataset
    except HaltError:
        raise  # Re-raise HaltError to stop the process
    except Exception as e:
        print(f"Error loading dataset {name}: {e}")
        print(traceback.format_exc())
        raise HaltError(f"Critical error loading dataset {name}: {e}")


def _load_hotpot_500() -> List[Dict[str, Any]]:
    from dspy.datasets import HotPotQA

    seed = 2024
    size = 500

    try:
        trainset = [
            x.with_inputs("question")
            for x in HotPotQA(train_seed=seed, train_size=size).train
        ]
    except Exception:
        raise Exception("Unable to download HotPotQA; please try again") from None

    data = []
    for row in reversed(trainset):
        d = row.toDict()
        del d["dspy_uuid"]
        del d["dspy_split"]
        data.append(d)

    return data


def _load_hotpot_300() -> List[Dict[str, Any]]:
    from dspy.datasets import HotPotQA

    seed = 42
    size = 300

    try:
        trainset = [
            x.with_inputs("question")
            for x in HotPotQA(train_seed=seed, train_size=size).train
        ]
    except Exception:
        raise Exception("Unable to download HotPotQA; please try again") from None

    data = []
    for row in trainset:
        d = row.toDict()
        del d["dspy_uuid"]
        del d["dspy_split"]
        data.append(d)

    return data


def _load_halu_eval_300() -> List[Dict[str, Any]]:
    import pandas as pd

    try:
        df = pd.read_parquet(
            "hf://datasets/pminervini/HaluEval/general/data-00000-of-00001.parquet"
        )
    except Exception:
        raise Exception("Unable to download HaluEval; please try again") from None

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
            },
        },
        {
            "text": "Who wrote Romeo and Juliet?",
            "label": "William Shakespeare",
            "metadata": {
                "context": "Romeo and Juliet is a famous play written by William Shakespeare."
            },
        },
        {
            "text": "What is 2 + 2?",
            "label": "4",
            "metadata": {"context": "Basic arithmetic: 2 + 2 equals 4."},
        },
        {
            "text": "What is the largest planet in our solar system?",
            "label": "Jupiter",
            "metadata": {
                "context": "Jupiter is the largest planet in our solar system."
            },
        },
        {
            "text": "Who painted the Mona Lisa?",
            "label": "Leonardo da Vinci",
            "metadata": {"context": "The Mona Lisa was painted by Leonardo da Vinci."},
        },
    ]


def _load_gsm8k() -> List[Dict[str, Any]]:
    """Load GSM8K dataset with 300 examples."""
    try:
        dataset = load_dataset("gsm8k", "main")
    except Exception:
        raise Exception("Unable to download gsm8k; please try again") from None

    train_data = dataset["train"].select(range(300))

    return [
        {
            "question": item["question"],
            "answer": item["answer"],
        }
        for item in train_data
    ]


def _load_hotpot_qa() -> List[Dict[str, Any]]:
    """Load HotpotQA dataset with 300 examples."""
    try:
        dataset = load_dataset("hotpot_qa", "distractor")
    except Exception:
        raise Exception("Unable to download HotPotQA; please try again") from None

    train_data = dataset["train"].select(range(300))

    return [
        {
            "question": item["question"],
            "answer": item["answer"],
            "context": item["context"],
        }
        for item in train_data
    ]


def _load_ai2_arc() -> List[Dict[str, Any]]:
    """Load AI2 ARC dataset with 300 examples."""
    try:
        dataset = load_dataset("ai2_arc", "ARC-Challenge")
    except Exception:
        raise Exception("Unable to download ai2_arc; please try again") from None

    train_data = dataset["train"].select(range(300))

    return [
        {
            "question": item["question"],
            "answer": item["answerKey"],
            "choices": item["choices"],
        }
        for item in train_data
    ]


def _load_truthful_qa() -> List[Dict]:
    """Load TruthfulQA dataset."""
    try:
        # Load both configurations
        try:
            gen_dataset = load_dataset("truthful_qa", "generation")
            mc_dataset = load_dataset("truthful_qa", "multiple_choice")
        except Exception:
            raise Exception(
                "Unable to download truthful_qa; please try again"
            ) from None

        # Combine data from both configurations
        data = []
        for gen_item, mc_item in zip(
            gen_dataset["validation"], mc_dataset["validation"]
        ):
            # Get correct answers from both configurations
            correct_answers = set(gen_item["correct_answers"])
            if "mc1_targets" in mc_item:
                correct_answers.update(
                    [
                        choice
                        for choice, label in zip(
                            mc_item["mc1_targets"]["choices"],
                            mc_item["mc1_targets"]["labels"],
                        )
                        if label == 1
                    ]
                )
            if "mc2_targets" in mc_item:
                correct_answers.update(
                    [
                        choice
                        for choice, label in zip(
                            mc_item["mc2_targets"]["choices"],
                            mc_item["mc2_targets"]["labels"],
                        )
                        if label == 1
                    ]
                )

            # Get all possible answers
            all_answers = set(
                gen_item["correct_answers"] + gen_item["incorrect_answers"]
            )
            if "mc1_targets" in mc_item:
                all_answers.update(mc_item["mc1_targets"]["choices"])
            if "mc2_targets" in mc_item:
                all_answers.update(mc_item["mc2_targets"]["choices"])

            # Create a single example with all necessary fields
            example = {
                "question": gen_item["question"],
                "answer": gen_item["best_answer"],
                "choices": list(all_answers),
                "correct_answer": gen_item["best_answer"],
                "input": gen_item["question"],  # For AnswerRelevance metric
                "output": gen_item["best_answer"],  # For output_key requirement
                "context": gen_item.get("context", ""),  # For context requirement
                "type": "TEXT",  # Set type to TEXT as required by Opik
                "category": gen_item["category"],
                "source": "MANUAL",  # Set source to MANUAL as required by Opik
                "correct_answers": list(
                    correct_answers
                ),  # Keep track of all correct answers
                "incorrect_answers": gen_item[
                    "incorrect_answers"
                ],  # Keep track of incorrect answers
            }

            # Ensure all required fields are present
            required_fields = [
                "question",
                "answer",
                "choices",
                "correct_answer",
                "input",
                "output",
                "context",
            ]
            if all(field in example and example[field] for field in required_fields):
                data.append(example)

        if not data:
            raise ValueError("No valid examples found in TruthfulQA dataset")

        return data
    except Exception as e:
        print(f"Error loading TruthfulQA dataset: {e}")
        print(traceback.format_exc())
        raise


def _load_cnn_dailymail() -> List[Dict]:
    """Load CNN Daily Mail dataset with 100 examples."""
    try:
        dataset = load_dataset("cnn_dailymail", "3.0.0", streaming=True)
    except Exception:
        raise Exception("Unable to download cnn_dailymail; please try again") from None

    train_data = dataset["validation"].take(100)

    return [
        {
            "article": item["article"],
            "highlights": item["highlights"],
        }
        for item in train_data
    ]


def _load_math_50():
    return [
        {"question": "What is (5 + 3) * 2 - 4?", "expected answer": "12"},
        {
            "question": "If you divide 20 by 4 and then add 7, what do you get?",
            "expected answer": "12",
        },
        {
            "question": "Start with 10, subtract 2, multiply the result by 3, then add 5.",
            "expected answer": "29",
        },
        {
            "question": "Add 6 and 4, then divide by 2, and finally multiply by 5.",
            "expected answer": "25",
        },
        {
            "question": "Take 15, subtract 3, add 2, then divide the result by 2.",
            "expected answer": "7",
        },
        {"question": "What is 7 * (6 - 2) + 1?", "expected answer": "29"},
        {
            "question": "If you multiply 8 by 3 and subtract 5, what is the result?",
            "expected answer": "19",
        },
        {
            "question": "Begin with 25, divide by 5, then multiply by 4.",
            "expected answer": "20",
        },
        {
            "question": "Subtract 9 from 17, then multiply the difference by 3.",
            "expected answer": "24",
        },
        {"question": "What is 10 + 5 * 3 - 8?", "expected answer": "17"},
        {"question": "Divide 36 by 6, then add 11.", "expected answer": "17"},
        {
            "question": "Start with 2, multiply by 9, subtract 7, and add 4.",
            "expected answer": "15",
        },
        {
            "question": "Add 12 and 8, divide by 4, and then subtract 1.",
            "expected answer": "4",
        },
        {
            "question": "Take 30, subtract 10, divide by 2, and add 7.",
            "expected answer": "17",
        },
        {"question": "What is (15 - 5) / 2 * 3?", "expected answer": "15"},
        {
            "question": "If you add 14 and 6, and then divide by 5, what do you get?",
            "expected answer": "4",
        },
        {
            "question": "Start with 50, divide by 10, multiply by 2, and subtract 3.",
            "expected answer": "7",
        },
        {
            "question": "Subtract 4 from 11, multiply by 5, and then add 2.",
            "expected answer": "37",
        },
        {"question": "What is 9 * 4 - 12 / 3?", "expected answer": "32"},
        {
            "question": "Divide 42 by 7, and then multiply by 3.",
            "expected answer": "18",
        },
        {
            "question": "Begin with 1, add 19, divide by 4, and multiply by 6.",
            "expected answer": "30",
        },
        {
            "question": "Subtract 6 from 21, then divide the result by 5.",
            "expected answer": "3",
        },
        {"question": "What is (8 + 7) * 2 - 9?", "expected answer": "21"},
        {
            "question": "If you multiply 7 by 5 and then subtract 11, what is the answer?",
            "expected answer": "24",
        },
        {
            "question": "Start with 3, multiply by 8, add 6, and then divide by 2.",
            "expected answer": "15",
        },
        {"question": "What is 3 * (10 - 4) + 5?", "expected answer": "23"},
        {
            "question": "If you multiply 12 by 2 and subtract 7, what is the result?",
            "expected answer": "17",
        },
        {
            "question": "Begin with 35, divide by 7, then multiply by 6.",
            "expected answer": "30",
        },
        {
            "question": "Subtract 11 from 20, then multiply the difference by 4.",
            "expected answer": "36",
        },
        {"question": "What is 15 + 3 * 7 - 9?", "expected answer": "27"},
        {"question": "Divide 63 by 9, then add 13.", "expected answer": "20"},
        {
            "question": "Start with 6, multiply by 5, subtract 8, and add 11.",
            "expected answer": "33",
        },
        {
            "question": "Add 18 and 6, divide by 3, and then subtract 4.",
            "expected answer": "4",
        },
        {
            "question": "Take 50, subtract 20, divide by 5, and add 9.",
            "expected answer": "15",
        },
        {"question": "What is (25 - 10) / 3 * 4?", "expected answer": "20"},
        {
            "question": "If you add 9 and 15, and then divide by 8, what do you get?",
            "expected answer": "3",
        },
        {
            "question": "Start with 40, divide by 5, multiply by 3, and subtract 7.",
            "expected answer": "17",
        },
        {
            "question": "Subtract 5 from 22, multiply by 2, and then divide by 6.",
            "expected answer": "5.666666666666667",
        },
        {"question": "What is 7 * 6 + 8 - 11?", "expected answer": "39"},
        {
            "question": "Divide 72 by 8, and then multiply by 5.",
            "expected answer": "45",
        },
        {
            "question": "Begin with 3, add 17, divide by 5, and multiply by 7.",
            "expected answer": "28",
        },
        {
            "question": "Subtract 9 from 31, then divide the result by 4.",
            "expected answer": "5.5",
        },
        {"question": "What is (11 + 9) * 3 - 15?", "expected answer": "45"},
        {
            "question": "If you multiply 8 by 7 and then subtract 19, what is the answer?",
            "expected answer": "37",
        },
        {
            "question": "Start with 2, multiply by 12, add 16, and then divide by 4.",
            "expected answer": "10",
        },
        {
            "question": "Add 13 and 19, then subtract 6, and finally divide by 2.",
            "expected answer": "13",
        },
        {
            "question": "Take 45, divide by 9, add 11, and then subtract 3.",
            "expected answer": "13",
        },
        {"question": "What is 18 - 4 * 3 + 7?", "expected answer": "13"},
        {
            "question": "If you divide 56 by 7 and then add 9, what do you get?",
            "expected answer": "17",
        },
        {
            "question": "Begin with 4, multiply by 9, subtract 12, and then divide by 6.",
            "expected answer": "4",
        },
    ]
