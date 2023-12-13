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

import comet_ml

from . import llm_trace_api, query_dsl


class API:
    def __init__(self, api_key: str):
        self._api = comet_ml.API(api_key=api_key, cache=False)

    def get_llm_trace_by_key(self, trace_key: str) -> llm_trace_api.LLMTraceAPI:
        """
        Get an API Trace object by key.

        Args:
            trace_key: str, key of the prompt or chain

        Returns: An LLMTraceAPI object that can be used to get or update trace data
        """
        matching_trace = self._api.get_experiment_by_key(trace_key)

        if matching_trace:
            return llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(
                matching_trace
            )
        else:
            raise ValueError(
                f"Failed to find any matching traces with the key {trace_key}"
            )

    def get_llm_trace_by_name(
        self, workspace: str, project_name: str, trace_name: str
    ) -> llm_trace_api.LLMTraceAPI:
        """
        Get an API Trace object by name.

        Args:
            workspace: str, name of the workspace
            project_name: str, name of the project
            trace_name: str, name of the prompt or chain

        Returns: An LLMTraceAPI object that can be used to get or update trace data
        """
        matching_trace = self._api.query(
            workspace, project_name, query_dsl.Other("Name") == trace_name
        )

        if len(matching_trace) == 0:
            raise ValueError(
                f"Failed to find any matching traces with the name {trace_name} in the project {project_name}"
            )
        elif len(matching_trace) == 1:
            return llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(
                matching_trace[0]
            )
        else:
            raise ValueError(
                f"Found multiple traces with the name {trace_name} in the project {project_name}"
            )
