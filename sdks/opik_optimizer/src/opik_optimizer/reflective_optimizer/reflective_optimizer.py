from opik.environment import get_tqdm_for_current_environment
import os
import logging

import litellm
from litellm.caching import Cache
from litellm.types.caching import LiteLLMCacheType

from .. import _throttle
from ..base_optimizer import BaseOptimizer

tqdm = get_tqdm_for_current_environment()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type=LiteLLMCacheType.DISK, disk_cache_dir=disk_cache_dir)

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

class ReflectiveOptimizer(BaseOptimizer):
    """
    The Reflective Optimizer uses reflective prompting to improve prompts based on failure modes
    identified during the evaluation process.

    This algorithm is best in-class and useful when you already have a complex prompt that you want
    to improve.
    """

    DEFAULT_ROUNDS = 10

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
