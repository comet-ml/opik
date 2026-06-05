from typing import Optional, TypedDict


class McpServerEnv(TypedDict, total=False):
    """Environment variables opik-mcp reads. Keys present depend on deployment."""

    OPIK_API_KEY: str
    COMET_WORKSPACE: str
    COMET_URL_OVERRIDE: str
    OPIK_URL: str


def build_mcp_env(
    api_key: Optional[str],
    workspace: Optional[str],
    base_url: str,
    api_url: str,
    use_local: bool,
    self_hosted_comet: bool,
) -> McpServerEnv:
    """Build the environment block the ``opik-mcp`` server expects, derived from
    the values the user just configured for the SDK.

    These are the variables read by opik-mcp's own settings (``OPIK_API_KEY``,
    ``COMET_WORKSPACE``, ``COMET_URL_OVERRIDE``, ``OPIK_URL``) — note opik-mcp
    reuses the Comet-platform env names, not the SDK's ``OPIK_`` prefixed ones:

    - Cloud: ``OPIK_API_KEY`` + ``COMET_WORKSPACE``.
    - Self-hosted Comet: also ``COMET_URL_OVERRIDE`` set to the instance base URL;
      opik-mcp derives the Opik REST base as ``COMET_URL_OVERRIDE + "/opik/api"``.
    - Local OSS: ``OPIK_URL`` set to the full Opik REST base (localhost serves
      ``/api``, not the default ``/opik/api``), ``default`` workspace, no API key.

    The workspace is passed verbatim because it has already been validated against
    the backend during configuration; it must not be transformed here.
    """
    env: McpServerEnv = {}

    if api_key is not None:
        env["OPIK_API_KEY"] = api_key

    if workspace is not None:
        env["COMET_WORKSPACE"] = workspace

    if self_hosted_comet:
        env["COMET_URL_OVERRIDE"] = base_url.rstrip("/")
    elif use_local:
        env["OPIK_URL"] = api_url

    return env
