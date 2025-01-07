from pytest import ExceptionInfo
from typing import Union
from . import evaluators


def format_error(
    path: str,
    test_case: Union[evaluators.PythonEvaluator, evaluators.BashEvaluator],
    excinfo: ExceptionInfo[BaseException],
) -> str:
    # Get the error type and message
    error_type = excinfo.type.__name__

    # Get the line number from the traceback
    traceback = excinfo.traceback
    last_entry = traceback[-1]  # Get the last frame in the traceback
    error_type = excinfo.type.__name__
    if error_type == "CalledProcessError":
        error = excinfo.value
        actual_line = 0

        error_msg = ""
        skip = True
        for line in error.stderr.split("\n"):
            if 'script.py", line' in line:
                skip = False
                line_part = line.split("line")[1].split(",")[0].strip()
                actual_line = test_case.start_line + int(line_part) - 1
            if not skip:
                error_msg += "\n" + line
    else:
        actual_line = test_case.start_line + last_entry.lineno
        error_msg = str(excinfo.value)

    # Format like a standard Python error
    return f"{path}:{actual_line}: {error_msg}"
