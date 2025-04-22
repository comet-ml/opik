from . import _logging, environment, error_tracking, package_version
from .api_objects.dataset import Dataset
from .api_objects.experiment.experiment_item import (
    ExperimentItemContent,
    ExperimentItemReferences,
)
from .api_objects.attachment import Attachment
from .api_objects.opik_client import Opik
from .api_objects.prompt import Prompt
from .api_objects.prompt.types import PromptType
from .api_objects.span import Span
from .api_objects.trace import Trace
from .configurator.configure import configure
from .decorator.tracker import flush_tracker, track
from .evaluation import evaluate, evaluate_experiment, evaluate_prompt
from .integrations.sagemaker import auth as sagemaker_auth
from .plugins.pytest.decorator import llm_unit
from .types import LLMProvider
from . import opik_context

_logging.setup()

__version__ = package_version.VERSION
__all__ = [
    "__version__",
    "Attachment",
    "evaluate",
    "evaluate_prompt",
    "evaluate_experiment",
    "ExperimentItemContent",
    "ExperimentItemReferences",
    "track",
    "flush_tracker",
    "Opik",
    "opik_context",
    "Trace",
    "Span",
    "Dataset",
    "llm_unit",
    "configure",
    "Prompt",
    "PromptType",
    "LLMProvider",
]

sagemaker_auth.setup_aws_sagemaker_session_hook()


if (
    error_tracking.enabled_in_config()
    and not environment.in_pytest()
    and error_tracking.randomized_should_enable_reporting()
):
    error_tracking.setup_sentry_error_tracker()
