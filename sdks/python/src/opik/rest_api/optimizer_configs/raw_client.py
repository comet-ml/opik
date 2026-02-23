import typing
from json.decoder import JSONDecodeError

from ..core.api_error import ApiError
from ..core.client_wrapper import SyncClientWrapper
from ..core.http_response import HttpResponse
from ..core.jsonable_encoder import jsonable_encoder
from ..core.pydantic_utilities import parse_obj_as
from ..core.request_options import RequestOptions
from ..types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
    OptimizerConfigCreateResponse,
)

OMIT = typing.cast(typing.Any, ...)


class RawOptimizerConfigsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def create_config(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        id: typing.Optional[str] = OMIT,
        blueprint: typing.Optional[typing.Dict[str, typing.Any]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[OptimizerConfigCreateResponse]:
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/optimizer-configs/",
            method="POST",
            json={
                "projectName": project_name,
                "id": id,
                "blueprint": blueprint,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizerConfigCreateResponse,
                    parse_obj_as(
                        type_=OptimizerConfigCreateResponse,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_blueprint(
        self,
        config_id: str,
        *,
        mask_id: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[OptimizerConfigBlueprint]:
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizer-configs/{jsonable_encoder(config_id)}/blueprint/retrieve",
            method="GET",
            params={
                "maskid": mask_id,
                "env": env,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizerConfigBlueprint,
                    parse_obj_as(
                        type_=OptimizerConfigBlueprint,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def update_values(
        self,
        config_id: str,
        *,
        values: typing.List[typing.Dict[str, str]],
        description: typing.Optional[str] = OMIT,
        id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[OptimizerConfigBlueprint]:
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizer-configs/{jsonable_encoder(config_id)}/values",
            method="POST",
            json={
                "id": id,
                "description": description,
                "values": values,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizerConfigBlueprint,
                    parse_obj_as(
                        type_=OptimizerConfigBlueprint,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def assign_envs(
        self,
        config_id: str,
        *,
        envs: typing.List[typing.Dict[str, str]],
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizer-configs/{jsonable_encoder(config_id)}/envs",
            method="POST",
            json={
                "envs": envs,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)
