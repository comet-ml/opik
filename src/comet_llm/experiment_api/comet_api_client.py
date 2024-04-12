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

import functools
import logging
import urllib.parse
import warnings
from typing import IO, Any, Dict, List, Optional

import requests  # type: ignore
import urllib3.exceptions

from .. import config, exceptions, semantic_version
from ..types import JSONEncodable
from . import error_codes_mapping, payload_constructor

ResponseContent = JSONEncodable

LOGGER = logging.getLogger(__name__)


class CometAPIClient:
    def __init__(self, api_key: str, comet_url: str, session: requests.Session):
        self._headers = {"Authorization": api_key}
        self._comet_url = comet_url
        self._session = session

        self.backend_version = semantic_version.SemanticVersion.parse(
            self.is_alive_ver()["version"]
        )

    def is_alive_ver(self) -> ResponseContent:
        return self._request(
            "GET",
            "api/isAlive/ver",
        )

    def create_experiment(
        self,
        type_: str,
        workspace: Optional[str],
        project: Optional[str],
    ) -> ResponseContent:
        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/create",
            json={"workspaceName": workspace, "projectName": project, "type": type_},
        )

    def get_experiment_metadata(self, experiment_key: str) -> ResponseContent:
        return self._request(
            "GET",
            "/api/rest/v2/experiment/metadata",
            params={"experimentKey": experiment_key},
        )

    def log_experiment_parameter(
        self, experiment_key: str, name: str, value: JSONEncodable
    ) -> ResponseContent:
        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/parameter",
            json={
                "experimentKey": experiment_key,
                "parameterName": name,
                "parameterValue": value,
            },
        )

    def log_experiment_metric(
        self, experiment_key: str, name: str, value: JSONEncodable
    ) -> ResponseContent:
        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/metric",
            json={
                "experimentKey": experiment_key,
                "metricName": name,
                "metricValue": value,
            },
        )

    def log_experiment_asset_with_io(
        self,
        experiment_key: str,
        name: str,
        file: IO,
        asset_type: str,
        extension: Optional[str] = None,
    ) -> ResponseContent:
        extension = name.split(".")[-1] if extension is None else extension

        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/upload-asset",
            params={
                "experimentKey": experiment_key,
                "fileName": name,
                "extension": extension,
                "type": asset_type,
            },
            files={"file": file},
        )

    def log_experiment_tags(
        self, experiment_key: str, tags: List[str]
    ) -> ResponseContent:
        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/tags",
            json={"experimentKey": experiment_key, "addedTags": tags},
        )

    def log_experiment_other(
        self, experiment_key: str, name: str, value: JSONEncodable
    ) -> ResponseContent:
        return self._request(
            "POST",
            "/api/rest/v2/write/experiment/log-other",
            json={
                "experimentKey": experiment_key,
                "key": name,
                "value": value,
            },
        )

    def log_chain(
        self,
        experiment_key: str,
        chain_asset: Dict[str, JSONEncodable],
        workspace: Optional[str] = None,
        project: Optional[str] = None,
        parameters: Optional[Dict[str, JSONEncodable]] = None,
        metrics: Optional[Dict[str, JSONEncodable]] = None,
        tags: Optional[List[str]] = None,
        others: Optional[Dict[str, JSONEncodable]] = None,
    ) -> ResponseContent:
        json = [
            {
                "experimentKey": experiment_key,
                "createExperimentRequest": {
                    "workspaceName": workspace,
                    "projectName": project,
                    "type": "LLM",
                },
                "parameters": payload_constructor.chain_parameters_payload(parameters),
                "metrics": payload_constructor.chain_metrics_payload(metrics),
                "others": payload_constructor.chain_others_payload(others),
                "tags": tags,
                "jsonAsset": {
                    "extension": "json",
                    "type": "llm_data",
                    "fileName": "comet_llm_data.json",
                    "file": chain_asset,
                },
            }
        ]  # we make a list because endpoint is designed for batches

        batched_response: Dict[str, Dict[str, Any]] = self._request(
            "POST",
            "api/rest/v2/write/experiment/llm",
            json=json,
        )
        sub_response = list(batched_response.values())[0]
        status = sub_response["status"]
        if status != 200:
            LOGGER.debug(
                "Failed to send trace: \nPayload %s, Response %s",
                str(json),
                str(batched_response),
            )
            error_code = sub_response["content"]["sdk_error_code"]
            raise exceptions.CometLLMException(error_codes_mapping.MESSAGES[error_code])

        return sub_response["content"]

    def _request(self, method: str, path: str, *args, **kwargs) -> ResponseContent:  # type: ignore
        url = urllib.parse.urljoin(self._comet_url, path)
        response = self._session.request(
            method=method,
            url=url,
            headers=self._headers,
            *args,
            **kwargs,
        )
        response.raise_for_status()

        return response.json()


@functools.lru_cache(maxsize=1)
def get(api_key: str) -> CometAPIClient:
    comet_url = config.comet_url()
    session = _get_session()
    comet_api_client = CometAPIClient(api_key, comet_url, session)

    return comet_api_client


def _get_session() -> requests.Session:
    session = requests.Session()

    if not config.tls_verification_enabled():
        # Only the set the verify if it's disabled. The current default for the verify attribute is
        # True but this way we will survive any change of the default value
        session.verify = False
        # Also filter the warning that urllib3 emits to not overflow the output with them
        warnings.filterwarnings(
            "ignore", category=urllib3.exceptions.InsecureRequestWarning
        )

    return session
