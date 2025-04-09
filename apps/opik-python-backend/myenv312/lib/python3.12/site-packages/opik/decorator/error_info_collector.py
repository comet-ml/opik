from opik.types import ErrorInfoDict
import traceback


def collect(exception: Exception) -> ErrorInfoDict:
    result: ErrorInfoDict = {
        "exception_type": type(exception).__name__,
        "traceback": "".join(
            traceback.TracebackException.from_exception(exception).format()
        ),
    }

    message = str(exception)
    if message != "":
        result["message"] = message

    return result
