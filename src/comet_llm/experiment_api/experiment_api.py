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

from typing import IO, Any, List, Optional

from . import comet_api_client, request_exception_wrapper
from .. import config


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
    
    @classmethod
    @request_exception_wrapper.wrap(check_on_prem=True)
    def create_new(cls, api_key: str, workspace: Optional[str] = None, project_name: Optional[str] = None):
        client = comet_api_client.get(api_key)
        response = client.create_experiment("LLM", workspace, project_name)

        experiment_api = cls(id=response["experimentKey"], comet_api_client=client, workspace=workspace, project_name=project_name)
        link = response["link"]
        experiment_api._project_url = link[: link.rfind("/")]

        return experiment_api

    @classmethod
    def from_existing_id(cls, id: str, api_key: str, initialize_parameters: Optional[bool] = True):
        client = comet_api_client.get(api_key)
        experiment_api = cls(id=id, comet_api_client=client)

        if initialize_parameters:
            experiment_api.initialize_parameters()

        return experiment_api

    @property
    def project_url(self) -> str:
        return self._project_url

    @property
    def id(self) -> str:
        return self._id

    @property
    def workspace(self) -> str:
        return self._workspace
    
    @property
    def project_name(self) -> str:
        return self._project_name
    
    def initialize_parameters(self) -> None:
        metadata = self._client.get_experiment_metadata(self._id)
        self._workspace = metadata["workspaceName"]
        self._project_name = metadata["projectName"]
        parsed_comet_url = config.comet_url().replace("/clientlib/", "")
        self._project_url = f"{parsed_comet_url}/{self._workspace}/{self._project_name}"

    @request_exception_wrapper.wrap(check_on_prem=True)
    def log_asset_with_io(self, name: str, file: IO, asset_type: str) -> None:
        self._client.log_experiment_asset_with_io(
            self._id, name=name, file=file, asset_type=asset_type
        )

    @request_exception_wrapper.wrap()
    def log_parameter(self, name: str, value: Any) -> None:
        self._client.log_experiment_parameter(self._id, name=name, value=value)

    @request_exception_wrapper.wrap()
    def log_metric(self, name: str, value: Any) -> None:
        self._client.log_experiment_metric(self._id, name=name, value=value)

    @request_exception_wrapper.wrap()
    def log_tags(self, tags: List[str]) -> None:
        self._client.log_experiment_tags(self._id, tags=tags)
