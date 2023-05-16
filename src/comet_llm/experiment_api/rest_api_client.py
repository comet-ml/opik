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
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import functools
import sys
import urllib.parse
from typing import IO, Optional

import requests  # type: ignore

from . import endpoints
from .. import exceptions, config
from ..types import JSONEncodable

ResponseContent = JSONEncodable


class RestApiClient:
    def __init__(self, api_key: str, comet_url: str):
        self._headers = {"Authorization": api_key}
        self._comet_url = comet_url

    @exceptions.reraiser(
        to_raise=exceptions.CometLLMRestApiException, to_catch=requests.RequestException
    )
    def create_experiment(
        self, workspace: Optional[str], project: Optional[str]
    ) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(self._comet_url, endpoints.CREATE_EXPERIMENT),
            json={
                "workspaceName": workspace,
                "projectName": project,
            },
            headers=self._headers,
        )
        response.raise_for_status()

        return response.json()

    @exceptions.reraiser(
        to_raise=exceptions.CometLLMRestApiException, to_catch=requests.RequestException
    )
    def log_experiment_parameter(
        self, experiment_key: str, name: str, value: JSONEncodable
    ) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(self._comet_url, endpoints.LOG_PARAMETER),
            json={
                "experimentKey": experiment_key,
                "parameterName": name,
                "parameterValue": value,
            },
            headers=self._headers,
        )
        response.raise_for_status()

        return response.json()

    @exceptions.reraiser(
        to_raise=exceptions.CometLLMRestApiException, to_catch=requests.RequestException
    )
    def log_experiment_asset_with_io(
        self, experiment_key: str, name: str, file: IO
    ) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(self._comet_url, endpoints.UPLOAD_ASSET),
            params={
                "experimentKey": experiment_key,
                "fileName": name,
            },
            files={"file": file},
            headers=self._headers,
        )
        response.raise_for_status()

        return response.json()


@functools.lru_cache(maxsize=0 if "pytest" in sys.modules else 1)
def get(api_key: str) -> RestApiClient:
    comet_url = config.comet_url()
    rest_api_client = RestApiClient(api_key, comet_url)

    return rest_api_client
