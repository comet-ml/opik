from .ai2_arc import ai2_arc
from .cnn_dailymail import cnn_dailymail
from .context7_eval import context7_eval
from .election_questions import election_questions
from .gsm8k import gsm8k
from .hover import hover
from .halu_eval import halu_eval_300
from .hotpot_qa import (
    hotpot,
    hotpot_300,
    hotpot_500,
)
from .ifbench import ifbench
from .medhallu import medhallu
from .rag_hallucinations import rag_hallucinations
from .ragbench import ragbench_sentence_relevance
from .tiny_test import tiny_test
from .truthful_qa import truthful_qa
from .pupa import pupa
from .driving_hazard import (
    driving_hazard,
    driving_hazard_50,
    driving_hazard_100,
    driving_hazard_test_split,
)

# TODO(opik): Remove the legacy helpers (hotpot_300/500, halu_eval_300,
# driving_hazard_50/100/test_split, etc.) once all downstream scripts and docs
# rely on the normalized dataset APIs.

__all__ = [
    "ai2_arc",
    "cnn_dailymail",
    "context7_eval",
    "driving_hazard_50",
    "driving_hazard_100",
    "driving_hazard_test_split",
    "driving_hazard",
    "election_questions",
    "gsm8k",
    "halu_eval_300",
    "hover",
    "hotpot_300",
    "hotpot_500",
    "hotpot",
    "ifbench",
    "medhallu",
    "pupa",
    "rag_hallucinations",
    "ragbench_sentence_relevance",
    "tiny_test",
    "truthful_qa",
]
