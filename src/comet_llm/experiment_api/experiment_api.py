# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

from typing import IO, List, Optional
from urllib import parse

from comet_llm.types import JSONEncodable

from .. import config, constants
from . import comet_api_client, request_exception_wrapper


class ExperimentAPI:
    def __init__(
        self,
        id: str,
        comet_api_client: comet_api_client.CometAPIClient,
        workspace: Optional[str] = None,
        project_name: Optional[str] = None,
    ):
        self._id = id
        self._client = comet_api_client
        self._workspace = workspace
        self._project_name = project_name
        if project_name is None or workspace is None:
            self._project_url = None
        else:
            self._project_url = self._build_comet_url()

    @classmethod
    @request_exception_wrapper.wrap(check_on_prem=True)
    def create_new(  # type: ignore
        cls,
        api_key: str,
        workspace: Optional[str] = None,
        project_name: Optional[str] = None,
    ):
        client = comet_api_client.get(api_key)
        response = client.create_experiment("LLM", workspace, project_name)

        experiment_api = cls(
            id=response[constants.EXPERIMENT_KEY_RESPONSE_KEY],
            comet_api_client=client,
            workspace=response[constants.WORKSPACE_RESPONSE_KEY],
            project_name=response[constants.PROJECT_NAME_RESPONSE_KEY],
        )

        return experiment_api

    @classmethod
    def from_existing_id(  # type: ignore
        cls, id: str, api_key: str, load_metadata: bool = True
    ):
        client = comet_api_client.get(api_key)
        experiment_api = cls(id=id, comet_api_client=client)

        if load_metadata:
            experiment_api.load_metadata()

        return experiment_api

    @property
    def project_url(self) -> Optional[str]:
        return self._project_url

    @property
    def id(self) -> str:
        return self._id

    @property
    def workspace(self) -> Optional[str]:
        return self._workspace

    @property
    def project_name(self) -> Optional[str]:
        return self._project_name

    def load_metadata(self) -> None:
        metadata = self._client.get_experiment_metadata(self._id)
        self._workspace = metadata[constants.WORKSPACE_RESPONSE_KEY]
        self._project_name = metadata[constants.PROJECT_NAME_RESPONSE_KEY]
        self._project_url = self._build_comet_url()

    def _build_comet_url(self) -> str:
        parsed_url = parse.urlparse(config.comet_url())
        parsed_comet_url = f"{parsed_url.scheme}://{parsed_url.netloc}"
        return f"{parsed_comet_url}/{self._workspace}/{self._project_name}"

    @request_exception_wrapper.wrap(check_on_prem=True)
    def log_asset_with_io(self, name: str, file: IO, asset_type: str) -> None:
        self._client.log_experiment_asset_with_io(
            self._id, name=name, file=file, asset_type=asset_type
        )

    @request_exception_wrapper.wrap()
    def log_parameter(self, name: str, value: JSONEncodable) -> None:
        self._client.log_experiment_parameter(self._id, name=name, value=value)

    @request_exception_wrapper.wrap()
    def log_metric(self, name: str, value: JSONEncodable) -> None:
        self._client.log_experiment_metric(self._id, name=name, value=value)

    @request_exception_wrapper.wrap()
    def log_tags(self, tags: List[str]) -> None:
        self._client.log_experiment_tags(self._id, tags=tags)

    @request_exception_wrapper.wrap()
    def log_other(self, name: str, value: JSONEncodable) -> None:
        self._client.log_experiment_other(self._id, name=name, value=value)
