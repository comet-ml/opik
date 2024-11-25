# This file was auto-generated by Fern from our API Definition.

import typing
from ..core.client_wrapper import SyncClientWrapper
from .types.find_feedback_definitions_request_type import (
    FindFeedbackDefinitionsRequestType,
)
from ..core.request_options import RequestOptions
from ..types.feedback_definition_page_public import FeedbackDefinitionPagePublic
from ..core.pydantic_utilities import parse_obj_as
from json.decoder import JSONDecodeError
from ..core.api_error import ApiError
from ..types.feedback_create import FeedbackCreate
from ..core.serialization import convert_and_respect_annotation_metadata
from ..types.feedback_public import FeedbackPublic
from ..core.jsonable_encoder import jsonable_encoder
from ..types.feedback_update import FeedbackUpdate
from ..core.client_wrapper import AsyncClientWrapper

# this is used as the default value for optional parameters
OMIT = typing.cast(typing.Any, ...)


class FeedbackDefinitionsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def find_feedback_definitions(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        type: typing.Optional[FindFeedbackDefinitionsRequestType] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> FeedbackDefinitionPagePublic:
        """
        Find Feedback definitions

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        type : typing.Optional[FindFeedbackDefinitionsRequestType]

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        FeedbackDefinitionPagePublic
            Feedback definitions resource

        Examples
        --------
        from Opik import OpikApi

        client = OpikApi()
        client.feedback_definitions.find_feedback_definitions()
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/feedback-definitions",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "type": type,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    FeedbackDefinitionPagePublic,
                    parse_obj_as(
                        type_=FeedbackDefinitionPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    def create_feedback_definition(
        self,
        *,
        request: FeedbackCreate,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
        """
        Get feedback definition

        Parameters
        ----------
        request : FeedbackCreate

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        from Opik import (
            CategoricalFeedbackDetailCreate,
            FeedbackCreate_Categorical,
            OpikApi,
        )

        client = OpikApi()
        client.feedback_definitions.create_feedback_definition(
            request=FeedbackCreate_Categorical(
                details=CategoricalFeedbackDetailCreate(),
            ),
        )
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/feedback-definitions",
            method="POST",
            json=convert_and_respect_annotation_metadata(
                object_=request, annotation=FeedbackCreate, direction="write"
            ),
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    def get_feedback_definition_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> FeedbackPublic:
        """
        Get feedback definition by id

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        FeedbackPublic
            Feedback definition resource

        Examples
        --------
        from Opik import OpikApi

        client = OpikApi()
        client.feedback_definitions.get_feedback_definition_by_id(
            id="id",
        )
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    FeedbackPublic,
                    parse_obj_as(
                        type_=FeedbackPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    def update_feedback_definition(
        self,
        id: str,
        *,
        request: FeedbackUpdate,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
        """
        Update feedback definition by id

        Parameters
        ----------
        id : str

        request : FeedbackUpdate

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        from Opik import (
            CategoricalFeedbackDetailUpdate,
            FeedbackUpdate_Categorical,
            OpikApi,
        )

        client = OpikApi()
        client.feedback_definitions.update_feedback_definition(
            id="id",
            request=FeedbackUpdate_Categorical(
                details=CategoricalFeedbackDetailUpdate(),
            ),
        )
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="PUT",
            json=convert_and_respect_annotation_metadata(
                object_=request, annotation=FeedbackUpdate, direction="write"
            ),
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    def delete_feedback_definition_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        Delete feedback definition by id

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        from Opik import OpikApi

        client = OpikApi()
        client.feedback_definitions.delete_feedback_definition_by_id(
            id="id",
        )
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)


class AsyncFeedbackDefinitionsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def find_feedback_definitions(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        type: typing.Optional[FindFeedbackDefinitionsRequestType] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> FeedbackDefinitionPagePublic:
        """
        Find Feedback definitions

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        type : typing.Optional[FindFeedbackDefinitionsRequestType]

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        FeedbackDefinitionPagePublic
            Feedback definitions resource

        Examples
        --------
        import asyncio

        from Opik import AsyncOpikApi

        client = AsyncOpikApi()


        async def main() -> None:
            await client.feedback_definitions.find_feedback_definitions()


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/feedback-definitions",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "type": type,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    FeedbackDefinitionPagePublic,
                    parse_obj_as(
                        type_=FeedbackDefinitionPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    async def create_feedback_definition(
        self,
        *,
        request: FeedbackCreate,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
        """
        Get feedback definition

        Parameters
        ----------
        request : FeedbackCreate

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        import asyncio

        from Opik import (
            AsyncOpikApi,
            CategoricalFeedbackDetailCreate,
            FeedbackCreate_Categorical,
        )

        client = AsyncOpikApi()


        async def main() -> None:
            await client.feedback_definitions.create_feedback_definition(
                request=FeedbackCreate_Categorical(
                    details=CategoricalFeedbackDetailCreate(),
                ),
            )


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/feedback-definitions",
            method="POST",
            json=convert_and_respect_annotation_metadata(
                object_=request, annotation=FeedbackCreate, direction="write"
            ),
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    async def get_feedback_definition_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> FeedbackPublic:
        """
        Get feedback definition by id

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        FeedbackPublic
            Feedback definition resource

        Examples
        --------
        import asyncio

        from Opik import AsyncOpikApi

        client = AsyncOpikApi()


        async def main() -> None:
            await client.feedback_definitions.get_feedback_definition_by_id(
                id="id",
            )


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return typing.cast(
                    FeedbackPublic,
                    parse_obj_as(
                        type_=FeedbackPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    async def update_feedback_definition(
        self,
        id: str,
        *,
        request: FeedbackUpdate,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
        """
        Update feedback definition by id

        Parameters
        ----------
        id : str

        request : FeedbackUpdate

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        import asyncio

        from Opik import (
            AsyncOpikApi,
            CategoricalFeedbackDetailUpdate,
            FeedbackUpdate_Categorical,
        )

        client = AsyncOpikApi()


        async def main() -> None:
            await client.feedback_definitions.update_feedback_definition(
                id="id",
                request=FeedbackUpdate_Categorical(
                    details=CategoricalFeedbackDetailUpdate(),
                ),
            )


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="PUT",
            json=convert_and_respect_annotation_metadata(
                object_=request, annotation=FeedbackUpdate, direction="write"
            ),
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)

    async def delete_feedback_definition_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        Delete feedback definition by id

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            Request-specific configuration.

        Returns
        -------
        None

        Examples
        --------
        import asyncio

        from Opik import AsyncOpikApi

        client = AsyncOpikApi()


        async def main() -> None:
            await client.feedback_definitions.delete_feedback_definition_by_id(
                id="id",
            )


        asyncio.run(main())
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/feedback-definitions/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, body=_response.text)
        raise ApiError(status_code=_response.status_code, body=_response_json)