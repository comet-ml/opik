import opik


def make_opik_client(
    *, workspace: str | None = None, api_key: str | None = None
) -> opik.Opik:
    """Construct an opik.Opik() with optional per-request workspace + api_key.

    When either argument is None, falls back to the SDK's env-based defaults
    (OPIK_WORKSPACE, OPIK_API_KEY). Used by every route in this bridge so the
    auth/workspace wiring stays in one place.
    """
    kwargs: dict[str, str] = {}
    if workspace:
        kwargs["workspace"] = workspace
    if api_key:
        kwargs["api_key"] = api_key
    return opik.Opik(**kwargs) if kwargs else opik.Opik()
