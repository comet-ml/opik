from .api_objects.prompt import Prompt
from .decorator.tracker import track, flush_tracker
from .api_objects.opik_client import Opik
from .api_objects.trace import Trace
from .api_objects.span import Span
from .api_objects.dataset import Dataset
from .api_objects.experiment.experiment_item import (
    ExperimentItemReferences,
    ExperimentItemContent,
)
from . import _logging
from .configurator.configure import configure
from . import package_version
from .plugins.pytest.decorator import llm_unit
from .evaluation import evaluate, evaluate_experiment
from .integrations.sagemaker import auth as sagemaker_auth

_logging.setup()

__version__ = package_version.VERSION
__all__ = [
    "__version__",
    "evaluate",
    "evaluate_experiment",
    "ExperimentItemContent",
    "ExperimentItemReferences",
    "track",
    "flush_tracker",
    "Opik",
    "Trace",
    "Span",
    "Dataset",
    "llm_unit",
    "configure",
    "Prompt",
]

sagemaker_auth.setup_aws_sagemaker_session_hook()
