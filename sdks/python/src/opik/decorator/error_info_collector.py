from typing import Dict, Any
import traceback

def collect(exception: Exception) -> Dict[str, Any]:
    result = {
        "exception_type": type(exception).__name__,
        "message": str(exception),
        "traceback": "".join(traceback.TracebackException.from_exception(exception).format())
    }

    return result