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

from typing import Optional, IO

import comet_ml
import urllib.parse
import requests

from . import endpoints
from .types import JSONEncodable

COMET_URL = "https://www.comet.com"


ResponseContent = JSONEncodable

class RestApiClient:
    def __init__(self, api_key: str):
        self._headers = {"Authorization": api_key}

    def create_experiment(self, workspace: Optional[str], project: Optional[str]) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(COMET_URL, endpoints.CREATE_EXPERIMENT),
            json={
                "workspaceName": workspace,
                "projectName": project,
            },
            headers=self._headers,
        )

        return response.json()
    

    def log_experiment_parameter(self, experiment_key: str, name: str, value: JSONEncodable) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(COMET_URL, endpoints.LOG_PARAMETER),
            json={
                "experimentKey": experiment_key,
                "parameterName": name,
                "parameterValue": value,
            },
            headers=self._headers,
        )

        return response.json()
    

    def log_experiment_asset_with_io(self, experiment_key: str, name: str, file: IO) -> ResponseContent:
        response = requests.post(
            urllib.parse.urljoin(COMET_URL, endpoints.UPLOAD_ASSET),
            params={
                "experimentKey": experiment_key,
                "fileName": name,
            },
            files={"file": file},
            headers=self._headers
        )
        return response.json()


@functools.lru_cache(maxsize=0 if "pytest" in sys.modules else 1)
def get(api_key: Optional[str] = None) -> RestApiClient:
    if api_key is None:
        comet_config = comet_ml.get_config()
        api_key = comet_ml.get_api_key(None, comet_config)

    rest_api_client = RestApiClient(api_key)
    return rest_api_client