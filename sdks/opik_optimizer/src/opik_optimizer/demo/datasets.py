import opik
from typing import Literal, List, Dict, Any
from .. import utils
from datasets import load_dataset
import traceback
from importlib.resources import files
import json
import warnings
from ..datasets import (
    hotpot_300,
    hotpot_500,
    halu_eval_300,
    tiny_test,
    gsm8k,
    ai2_arc,
    truthful_qa,
    cnn_dailymail,
    ragbench_sentence_relevance,
    election_questions,
    medhallu,
    rag_hallucinations,
)

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
        "ragbench_sentence_relevance",
        "election_questions",
        "medhallu",
        "rag_hallucinations",
    ],
    test_mode: bool = False,
    seed: int = 42,
) -> opik.Dataset:
    """Get or create a dataset from HuggingFace, using the provided seed for sampling."""
    warnings.warn(
        "This function is deprecated. Please use the datasets directly from opik_optimizer.datasets module instead."
        " For example: opik_optimizer.datasets.truthful_qa() or opik_optimizer.datasets.rag_hallucination()",
        DeprecationWarning,
        stacklevel=2
    )
    if name == "hotpot-300":
        dataset = hotpot_300(test_mode)
    elif name == "hotpot-500":
        dataset = hotpot_500(test_mode)
    elif name == "halu-eval-300":
        dataset = halu_eval_300(test_mode)
    elif name == "tiny-test":
        dataset = tiny_test()
    elif name == "gsm8k":
        dataset = gsm8k(test_mode)
    elif name == "hotpot_qa":
        raise HaltError("HotpotQA dataset is no longer available in the demo datasets.")
    elif name == "ai2_arc":
        dataset = ai2_arc(test_mode)
    elif name == "truthful_qa":
        dataset = truthful_qa(test_mode)
    elif name == "cnn_dailymail":
        dataset = cnn_dailymail(test_mode)
    elif name == "ragbench_sentence_relevance":
        dataset = ragbench_sentence_relevance(test_mode)
    elif name == "election_questions":
        dataset = election_questions(test_mode)
    elif name == "medhallu":
        dataset = medhallu(test_mode)
    elif name == "rag_hallucinations":
        dataset = rag_hallucinations(test_mode)
    else:
        raise HaltError(f"Unknown dataset: {name}")

    return dataset
