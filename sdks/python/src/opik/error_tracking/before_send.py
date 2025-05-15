from typing import Optional
import sentry_sdk
from . import user_details
from .error_filtering import sentry_filter_chain
from .types import Event, Hint
from . import environment_details


def callback(event: Event, hint: Hint) -> Optional[Event]:
    """
    Used to filter events and provide them with extra details that could not
    be collected during Sentry client initialization.
    """

    is_valid = sentry_filter_chain.validate(event, hint)

    if not is_valid:
        return None

    _try_add_fingerprint(event)

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

    opik_sdk_context = environment_details.collect_context_once()
    tags = environment_details.collect_tags_once()

    scope = sentry_sdk.get_current_scope()
    scope.set_context("opik-sdk-context", opik_sdk_context)
    scope.set_tags(tags)

    # Also write to event because sometimes sentry ignores the scope (TODO: understand why)
    event["contexts"]["opik-sdk-context"] = opik_sdk_context
    event["tags"] = {**event.get("tags", {}), **tags}

    # Put into event all the information that depends on
    # configuration which might be set AFTER opik is imported.


def _try_add_fingerprint(event: Event) -> None:
    try:
        if not (
            "extra" in event
            and "error_tracking_extra" in event["extra"]
            and "fingerprint" in event["extra"]["error_tracking_extra"]
        ):
            return

        event["fingerprint"] = event["extra"]["error_tracking_extra"]["fingerprint"]
    except Exception:
        pass
