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

from typing import IO, Any, List, Optional

from . import comet_api_client


class ExperimentAPI:
    def __init__(
        self,
        api_key: str,
        workspace: Optional[str],
        project_name: Optional[str],
    ):
        self._client = comet_api_client.get(api_key)
        self._initialize_experiment(workspace, project_name)

    @property
    def link(self) -> str:
        return self._link

    @property
    def project_link(self) -> str:
        return self._project_link

    def _initialize_experiment(
        self, workspace: Optional[str] = None, project_name: Optional[str] = None
    ) -> None:
        response = self._client.create_experiment("LLM", workspace, project_name)
        self._experiment_key = response["experimentKey"]
        self._initialize_links(response["link"])

    def _initialize_links(self, link: str) -> None:
        self._link = link
        self._project_link = link[: link.rfind("/")]

    def log_asset_with_io(self, name: str, file: IO, asset_type: str) -> None:
        self._client.log_experiment_asset_with_io(
            self._experiment_key, name=name, file=file, asset_type=asset_type
        )

    def log_parameter(self, name: str, value: Any) -> None:
        self._client.log_experiment_parameter(
            self._experiment_key, name=name, value=value
        )

    def log_metric(self, name: str, value: Any) -> None:
        self._client.log_experiment_metric(self._experiment_key, name=name, value=value)

    def log_tags(self, tags: List[str]) -> None:
        self._client.log_experiment_tags(self._experiment_key, tags=tags)
