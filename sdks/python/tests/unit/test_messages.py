from datetime import datetime
from threading import RLock

from opik.message_processing import messages


def test_messages__all_fields_are_serializable():
    payload_dict = {
        "trace_id": "1234",
        "project_name": "TestProject",
        "name": "TestName",
        "start_time": datetime.now(),
        "end_time": None,
        "input": {"key": "value"},
        "output": None,
        "metadata": None,
        "tags": None,
        "error_info": None,
        "thread_id": None,
        "last_updated_at": datetime.now(),
        "ttft": None,
    }

    message = messages.CreateTraceMessage(**payload_dict)

    result = message.as_payload_dict()
    payload_dict["id"] = payload_dict.pop("trace_id")

    assert result == payload_dict


def test_messages__not_all_fields_are_serializable():
    """
    Even if not all fields of the message are serializable, as_payload_dict() should still work
    """
    non_serializable_lock = RLock()

    payload_dict = {
        "trace_id": "1234",
        "project_name": "TestProject",
        "name": "TestName",
        "start_time": datetime.now(),
        "end_time": None,
        "input": {"key": "value"},
        "output": {
            "key": "value",
            "lock": non_serializable_lock,
        },
        "metadata": None,
        "tags": None,
        "error_info": None,
        "thread_id": None,
        "last_updated_at": datetime.now(),
        "ttft": None,
    }

    message = messages.CreateTraceMessage(**payload_dict)

    result = message.as_payload_dict()
    payload_dict["id"] = payload_dict.pop("trace_id")

    assert result == payload_dict
