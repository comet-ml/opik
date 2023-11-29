import json

import box
import pytest
from testix import *

from comet_llm.exceptions import exceptions
from comet_llm.experiment_api import failed_response_handler


def test_wrap__request_exception_non_llm_project_sdk_code__log_specifc_message_in_exception():
    exception = Exception()
    exception.response = box.Box(text=json.dumps({"sdk_error_code": 34323}))

    expected_log_message = "Failed to send prompt to the specified project as it is not an LLM project, please specify a different project name."


    with pytest.raises(exceptions.CometLLMException) as excinfo:
        failed_response_handler.handle(exception)

    assert excinfo.value.args == (expected_log_message, )