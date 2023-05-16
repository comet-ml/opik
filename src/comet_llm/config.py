import comet_ml
import functools

@functools.lru_cache(maxsize=1)
def _comet_ml_config():
    return comet_ml.get_config()

def workspace():
    return _comet_ml_config()["comet.workspace"]

def project_name():
    return _comet_ml_config()["comet.project_name"]

def comet_url():
    return comet_ml.get_backend_address(_comet_ml_config())

def api_key():
    comet_ml_config = _comet_ml_config()
    api_key = comet_ml.get_api_key(None, comet_ml_config)
    return api_key

