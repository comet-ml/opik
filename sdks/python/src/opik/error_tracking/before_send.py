from typing import Optional

import opik.environment
from . import user_details
from .error_filtering import sentry_filter_chain
from .types import Event, Hint


def callback(event: Event, hint: Hint) -> Optional[Event]:
    """
    Used to filter events and provide them with extra details that could not
    be collected during Sentry client initialization.
    """

    is_valid = sentry_filter_chain.validate(event, hint)

    if not is_valid:
        return None

    try:
        _add_extra_details(event)
    except Exception:
        return None

    return event


def _add_extra_details(event: Event) -> None:
    if "user" in event:
        event["user"]["id"] = user_details.get_id()
    else:
        event["user"] = {"id": user_details.get_id()}

    event["tags"] = {"installation_type": opik.environment.get_installation_type()}

    if event["contexts"].get("opik_error_handled") is False:
        event["tags"]["opik_error_handled"] = False
    else:
        event["tags"]["opik_error_handled"] = True

    # Put into event all the information that depends on
    # configuration which might be set AFTER opik is imported.
