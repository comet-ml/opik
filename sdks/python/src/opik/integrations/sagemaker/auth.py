import logging
import os
import httpx

from typing import Any

import opik.hooks

LOGGER = logging.getLogger(__name__)


def _in_aws_sagemaker() -> bool:
    return os.getenv("AWS_PARTNER_APP_AUTH") is not None


class SagemakerAuth(httpx.Auth):
    def __init__(self, auth_provider: Any) -> None:
        self.auth_provider = auth_provider

    def auth_flow(self, request):  # type: ignore
        if not _in_aws_sagemaker():
            yield request

        url, signed_headers = self.auth_provider.get_signed_request(
            request.url, request.method, request.headers, request.content
        )

        request.url = url
        request.headers.update(signed_headers)

        yield request


def _setup_aws_sagemaker_session_hook() -> None:
    import sagemaker

    auth_provider = sagemaker.PartnerAppAuthProvider()
    sagemaker_auth = SagemakerAuth(auth_provider)

    def sagemaker_auth_client_hook(client: httpx.Client) -> None:
        client.auth = sagemaker_auth

    opik.hooks.httpx_client_hook = sagemaker_auth_client_hook


def try_login_aws_sagemaker() -> None:
    """
    This auth integration is based on
    Sagemaker Partner Onboarding Guide v0.10.2, pages 14-16
    """

    if not _in_aws_sagemaker():
        return

    LOGGER.debug("AWS partner SDK initialization commenced")

    import partner_sdk

    url = os.getenv("AWS_PARTNER_APP_URL")
    api_key = os.getenv("PARTNER_APP_API_KEY")

    partner_sdk.init(
        api_key=api_key,
        url=url,
    )

    LOGGER.debug("AWS partner SDK initialized with app server URL: %s", url)

    _setup_aws_sagemaker_session_hook()
