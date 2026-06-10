from ... import llm_constants

PROJECT_NAME = "e2e-harbor-integration-tests"

AGENT_NAME = "terminus-2"
MODEL_NAME = llm_constants.OPENAI_GPT_NANO
TIMEOUT_SEC = 20

DATASET_NAME = "terminal-bench"
DATASET_VERSION = "2.0"
TASK_NAMES = ["fix-git", "overfull-hbox"]
