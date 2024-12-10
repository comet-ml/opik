from opik.types import ErrorInfoDict
import traceback


def collect(exception: Exception) -> ErrorInfoDict:
    result: ErrorInfoDict = {
        "exception_type": type(exception).__name__,
        "message": str(exception),
        "traceback": "".join(
            traceback.TracebackException.from_exception(exception).format()
        ),
    }

    return result
