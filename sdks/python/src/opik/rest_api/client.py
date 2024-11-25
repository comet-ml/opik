# This file was auto-generated by Fern from our API Definition.

import typing
from .environment import OpikApiEnvironment
import httpx
from .core.client_wrapper import SyncClientWrapper
from .system_usage.client import SystemUsageClient
from .datasets.client import DatasetsClient
from .experiments.client import ExperimentsClient
from .feedback_definitions.client import FeedbackDefinitionsClient
from .projects.client import ProjectsClient
from .prompts.client import PromptsClient
from .spans.client import SpansClient
from .traces.client import TracesClient
from .core.request_options import RequestOptions
from .core.pydantic_utilities import parse_obj_as
from json.decoder import JSONDecodeError
from .core.api_error import ApiError
from .core.client_wrapper import AsyncClientWrapper
from .system_usage.client import AsyncSystemUsageClient
from .datasets.client import AsyncDatasetsClient
from .experiments.client import AsyncExperimentsClient
from .feedback_definitions.client import AsyncFeedbackDefinitionsClient
from .projects.client import AsyncProjectsClient
from .prompts.client import AsyncPromptsClient
from .spans.client import AsyncSpansClient
from .traces.client import AsyncTracesClient


class OpikApi:
    """
    Use this class to access the different functions within the SDK. You can instantiate any number of clients with different configuration that will propagate to these functions.

    Parameters
    ----------
    base_url : typing.Optional[str]
        The base url to use for requests from the client.

    environment : OpikApiEnvironment
        The environment to use for requests from the client. from .environment import OpikApiEnvironment



        Defaults to OpikApiEnvironment.DEFAULT



    timeout : typing.Optional[float]
        The timeout to be used, in seconds, for requests. By default the timeout is 60 seconds, unless a custom httpx client is used, in which case this default is not enforced.

    follow_redirects : typing.Optional[bool]
        Whether the default httpx client follows redirects or not, this is irrelevant if a custom httpx client is passed in.

    httpx_client : typing.Optional[httpx.Client]
        The httpx client to use for making requests, a preconfigured client is used by default, however this is useful should you want to pass in any custom httpx configuration.

    Examples
    --------
    from Opik import OpikApi

    client = OpikApi()
    """

    def __init__(
        self,
        *,
        base_url: typing.Optional[str] = None,
        environment: OpikApiEnvironment = OpikApiEnvironment.DEFAULT,
        timeout: typing.Optional[float] = None,
        follow_redirects: typing.Optional[bool] = True,
        httpx_client: typing.Optional[httpx.Client] = None,
    ):
        _defaulted_timeout = (
            timeout if timeout is not None else 60 if httpx_client is None else None
        )
        self._client_wrapper = SyncClientWrapper(
            base_url=_get_base_url(base_url=base_url, environment=environment),
            httpx_client=httpx_client
            if httpx_client is not None
            else httpx.Client(
                timeout=_defaulted_timeout, follow_redirects=follow_redirects
            )
            if follow_redirects is not None
            else httpx.Client(timeout=_defaulted_timeout),
            timeout=_defaulted_timeout,
        )
        self.system_usage = SystemUsageClient(client_wrapper=self._client_wrapper)
        self.datasets = DatasetsClient(client_wrapper=self._client_wrapper)
        self.experiments = ExperimentsClient(client_wrapper=self._client_wrapper)
        self.feedback_definitions = FeedbackDefinitionsClient(
            client_wrapper=self._client_wrapper
        )
        self.projects = ProjectsClient(client_wrapper=self._client_wrapper)
        self.prompts = PromptsClient(client_wrapper=self._client_wrapper)
        self.spans = SpansClient(client_wrapper=self._client_wrapper)
        self.traces = TracesClient(client_wrapper=self._client_wrapper)

    def is_alive(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.Optional[typing.Any]:
        """
        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        typing.Optional[typing.Any]
            default response

        Examples
        --------
        from Opik import OpikApi

        client = OpikApi()
        client.is_alive()
        """
        _response = self._client_wrapper.httpx_client.request(
            "is-alive/ping",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    typing.Optional[typing.Any],
                    parse_obj_as(
                        type_=typing.Optional[typing.Any],  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    def version(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.Optional[typing.Any]:
        """
        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        typing.Optional[typing.Any]
            default response

        Examples
        --------
        from Opik import OpikApi

        client = OpikApi()
        client.version()
        """
        _response = self._client_wrapper.httpx_client.request(
            "is-alive/ver",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    typing.Optional[typing.Any],
                    parse_obj_as(
                        type_=typing.Optional[typing.Any],  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)


class AsyncOpikApi:
    """
    Use this class to access the different functions within the SDK. You can instantiate any number of clients with different configuration that will propagate to these functions.

    Parameters
    ----------
    base_url : typing.Optional[str]
        The base url to use for requests from the client.

    environment : OpikApiEnvironment
        The environment to use for requests from the client. from .environment import OpikApiEnvironment



        Defaults to OpikApiEnvironment.DEFAULT



    timeout : typing.Optional[float]
        The timeout to be used, in seconds, for requests. By default the timeout is 60 seconds, unless a custom httpx client is used, in which case this default is not enforced.

    follow_redirects : typing.Optional[bool]
        Whether the default httpx client follows redirects or not, this is irrelevant if a custom httpx client is passed in.

    httpx_client : typing.Optional[httpx.AsyncClient]
        The httpx client to use for making requests, a preconfigured client is used by default, however this is useful should you want to pass in any custom httpx configuration.

    Examples
    --------
    from Opik import AsyncOpikApi

    client = AsyncOpikApi()
    """

    def __init__(
        self,
        *,
        base_url: typing.Optional[str] = None,
        environment: OpikApiEnvironment = OpikApiEnvironment.DEFAULT,
        timeout: typing.Optional[float] = None,
        follow_redirects: typing.Optional[bool] = True,
        httpx_client: typing.Optional[httpx.AsyncClient] = None,
    ):
        _defaulted_timeout = (
            timeout if timeout is not None else 60 if httpx_client is None else None
        )
        self._client_wrapper = AsyncClientWrapper(
            base_url=_get_base_url(base_url=base_url, environment=environment),
            httpx_client=httpx_client
            if httpx_client is not None
            else httpx.AsyncClient(
                timeout=_defaulted_timeout, follow_redirects=follow_redirects
            )
            if follow_redirects is not None
            else httpx.AsyncClient(timeout=_defaulted_timeout),
            timeout=_defaulted_timeout,
        )
        self.system_usage = AsyncSystemUsageClient(client_wrapper=self._client_wrapper)
        self.datasets = AsyncDatasetsClient(client_wrapper=self._client_wrapper)
        self.experiments = AsyncExperimentsClient(client_wrapper=self._client_wrapper)
        self.feedback_definitions = AsyncFeedbackDefinitionsClient(
            client_wrapper=self._client_wrapper
        )
        self.projects = AsyncProjectsClient(client_wrapper=self._client_wrapper)
        self.prompts = AsyncPromptsClient(client_wrapper=self._client_wrapper)
        self.spans = AsyncSpansClient(client_wrapper=self._client_wrapper)
        self.traces = AsyncTracesClient(client_wrapper=self._client_wrapper)

    async def is_alive(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.Optional[typing.Any]:
        """
        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        typing.Optional[typing.Any]
            default response

        Examples
        --------
        import asyncio

        from Opik import AsyncOpikApi

        client = AsyncOpikApi()


        async def main() -> None:
            await client.is_alive()


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            "is-alive/ping",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    typing.Optional[typing.Any],
                    parse_obj_as(
                        type_=typing.Optional[typing.Any],  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    async def version(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.Optional[typing.Any]:
        """
        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        typing.Optional[typing.Any]
            default response

        Examples
        --------
        import asyncio

        from Opik import AsyncOpikApi

        client = AsyncOpikApi()


        async def main() -> None:
            await client.version()


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            "is-alive/ver",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    typing.Optional[typing.Any],
                    parse_obj_as(
                        type_=typing.Optional[typing.Any],  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)


def _get_base_url(
    *, base_url: typing.Optional[str] = None, environment: OpikApiEnvironment
) -> str:
    if base_url is not None:
        return base_url
    elif environment is not None:
        return environment.value
    else:
        raise Exception(
            "Please pass in either base_url or environment to construct the client"
        )