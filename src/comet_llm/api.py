from typing import Optional, Dict, Any
from .types import JSONEncodable
import comet_ml


def log_prompt(
    prompt: JSONEncodable,
    output: JSONEncodable,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
    api_key: Optional[str] = None,
    prompt_template: Optional[JSONEncodable] = None,
    prompt_template_variables: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    start_timestamp: Optional[int] = None,
    end_timestamp: Optional[int] = None,
    duration: Optional[int] = None,
):
    pass
