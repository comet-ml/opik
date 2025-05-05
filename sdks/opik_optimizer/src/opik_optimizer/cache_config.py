import os
from pathlib import Path
import litellm
from litellm.caching import Cache

# Configure cache directory
CACHE_DIR = os.path.expanduser("~/.litellm_cache")
Path(CACHE_DIR).mkdir(parents=True, exist_ok=True)

# Configure cache settings
CACHE_CONFIG = {
    "type": "disk",
    "disk_cache_dir": CACHE_DIR,
    "max_size": 10000,  # Maximum number of items in cache
    "ttl": 7 * 24 * 60 * 60,  # 7 days in seconds
}

def initialize_cache():
    """Initialize the LiteLLM cache with custom configuration."""
    litellm.cache = Cache(**CACHE_CONFIG)
    return litellm.cache

def clear_cache():
    """Clear the LiteLLM cache."""
    if litellm.cache:
        litellm.cache.clear() 