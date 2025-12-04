from . import _logging, environment, error_tracking, package_version
from .api_objects.attachment import Attachment
from .api_objects.dataset import Dataset
from .api_objects.experiment.experiment_item import (
    ExperimentItemContent,
    ExperimentItemReferences,
)
from .api_objects.opik_client import Opik
from .api_objects.prompt import Prompt, ChatPrompt
from .api_objects.prompt.types import PromptType
from .api_objects.span import Span
from .api_objects.trace import Trace
from .configurator.configure import configure
from .decorator.tracker import flush_tracker, track
from .evaluation import (
    evaluate,
    evaluate_experiment,
    evaluate_on_dict_items,
    evaluate_prompt,
)
from .integrations.sagemaker import auth as sagemaker_auth
from .plugins.pytest.decorator import llm_unit
from .types import LLMProvider
from . import opik_context
from .tracing_runtime_config import (
    is_tracing_active,
    reset_tracing_to_config_default,
    set_tracing_active,
)
from .decorator.context_manager.span_context_manager import start_as_current_span
from .decorator.context_manager.trace_context_manager import start_as_current_trace
from .simulation import SimulatedUser, run_simulation
from .api_objects.local_recording import record_traces_locally


_logging.setup()

__version__ = package_version.VERSION
__all__ = [
    "__version__",
    "Attachment",
    "evaluate",
    "evaluate_prompt",
    "evaluate_experiment",
    "evaluate_on_dict_items",
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
    "ChatPrompt",
    "PromptType",
    "LLMProvider",
    "reset_tracing_to_config_default",
    "set_tracing_active",
    "is_tracing_active",
    "start_as_current_span",
    "start_as_current_trace",
    "SimulatedUser",
    "run_simulation",
    "record_traces_locally",
]

sagemaker_auth.setup_aws_sagemaker_session_hook()


if (
    error_tracking.enabled_in_config()
    and not environment.in_pytest()
    and error_tracking.randomized_should_enable_reporting()
):
    error_tracking.setup_sentry_error_tracker()
