import typing

from ..core.client_wrapper import SyncClientWrapper
from ..core.request_options import RequestOptions
from ..types.optimizer_config_detail import (
    OptimizerConfigBlueprint,
    OptimizerConfigCreateResponse,
)
from .raw_client import RawOptimizerConfigsClient

OMIT = typing.cast(typing.Any, ...)


class OptimizerConfigsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._raw_client = RawOptimizerConfigsClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> RawOptimizerConfigsClient:
        return self._raw_client

    def create_config(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        id: typing.Optional[str] = OMIT,
        blueprint: typing.Optional[typing.Dict[str, typing.Any]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> OptimizerConfigCreateResponse:
        _response = self._raw_client.create_config(
            project_name=project_name,
            project_id=project_id,
            id=id,
            blueprint=blueprint,
            request_options=request_options,
        )
        return _response.data

    def get_blueprint(
        self,
        *,
        project_name: typing.Optional[str] = None,
        env: typing.Optional[str] = None,
        mask_id: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> OptimizerConfigBlueprint:
        _response = self._raw_client.get_blueprint(
            project_name=project_name,
            env=env,
            mask_id=mask_id,
            request_options=request_options,
        )
        return _response.data

