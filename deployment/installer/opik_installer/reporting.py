import os
import requests
import platform
from opik_installer import version

def _add_event_metadata(event):
    event["event_properties"]["opik_installer_version"] = version.__version__
    event["event_properties"]["os"] = platform.system()
    event["event_properties"]["os_version"] = platform.version()
    event["event_properties"]["architecture"] = platform.machine()
    
    return event

def _create_install_event(success: bool, failure_reason: str = None):
    event = {
        "user_id": "Anonymous",
        "event_type": "opik_installer_install",
        "event_properties": {
            "successful": success,
        }
    }

    if failure_reason:
        event["event_properties"]["failure_reason"] = failure_reason

    event = _add_event_metadata(event)
    return event

def _create_upgrade_event(success: bool, failure_reason: str = None):
    event = {
        "user_id": "Anonymous",
        "event_type": "opik_installer_upgrade",
        "event_properties": {
            "successful": success,
        }
    }

    if failure_reason:
        event["event_properties"]["failure_reason"] = failure_reason

    event = _add_event_metadata(event)
    return event

def is_reporting_disabled():
    if "opik_reporting_enabled" in os.environ:
        return os.environ["OPIK_REPORTING_ENABLED"] == "false"
    
    return False

def submit_event(upgrade_called: bool, success: bool, failure_reason: str = None):
    # Do not submit events if reporting is disabled
    if is_reporting_disabled():
        return
    
    if upgrade_called:
        event = _create_upgrade_event(success, failure_reason)
    else:
        event = _create_install_event(success, failure_reason)

    headers = {"Content-Type": "application/json"}

    try:
        response = requests.request(
            "POST",
            "https://stats.comet.com/notify/event/",
            json = event,
            headers = headers
        )
        if response.status_code != 200:
            return False
    except Exception as e:
        return False
    
    return True
