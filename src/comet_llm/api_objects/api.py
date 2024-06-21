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

from typing import List, Optional

import comet_ml

from .. import experiment_info, logging_messages, query_dsl
from . import llm_trace_api

# TODO: make the decision about dependencies from comet-ml. Address testing.


class API:
    def __init__(self, api_key: Optional[str] = None) -> None:
        """
        API class for accessing and updating prompt information.

        Args:
            api_key: You private Comet API key

        Example:
            ```python linenums="1"
            import comet_llm

            comet_llm.init()

            api = comet_llm.API()
            ```
        """
        experiment_info_ = experiment_info.get(
            api_key,
            api_key_not_found_message=logging_messages.API_KEY_NOT_FOUND_MESSAGE
            % "API",
        )
        self._api = comet_ml.API(api_key=experiment_info_.api_key, cache=False)

    def get_llm_trace_by_id(self, trace_id: str) -> llm_trace_api.LLMTraceAPI:
        """
        Get an API Trace object by id.

        Args:
            trace_id: Key of the prompt or chain

        Returns: An LLMTraceAPI object that can be used to get or update trace data
        """
        matching_trace = self._api.get_experiment_by_key(trace_id)

        if matching_trace is None:
            raise ValueError(f"Failed to find any matching traces with ID {trace_id}")

        return llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(matching_trace)

    def get_llm_trace_by_name(
        self, workspace: str, project_name: str, trace_name: str
    ) -> llm_trace_api.LLMTraceAPI:
        """
        Get an API Trace object by name.

        Args:
            workspace: Name of the workspace.
            project_name: Name of the project.
            trace_name: Name of the prompt or chain.

        Returns: An LLMTraceAPI object that can be used to get or update trace data
        """
        matching_trace = self._api.query(
            workspace, project_name, query_dsl.Other("Name") == trace_name
        )

        if len(matching_trace) == 0:
            raise ValueError(
                f"Failed to find any matching traces with the name {trace_name} in the project {project_name}"
            )
        elif len(matching_trace) > 1:
            raise ValueError(
                f"Found multiple traces with the name {trace_name} in the project {project_name}"
            )

        return llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(matching_trace[0])

    def query(
        self, workspace: str, project_name: str, query: str
    ) -> List[llm_trace_api.LLMTraceAPI]:
        """
        Fetch LLM Trace based on a query. Currently it is only possible to use
        trace metadata or details fields to filter the traces.

        Args:
            workspace: Name of the workspace
            project_name: Name of the project
            query: Query to use

        Returns: A list of LLMTrace objects

        Note:
            The `query` object takes the form of (QUERY_VARIABLE OPERATOR VALUE) with:

            * QUERY_VARIABLE is either TraceMetadata, Duration, Timestamp.
            * OPERATOR is any standard mathematical operators `<=`, `>=`, `!=`, `<`, `>`.

            It is also possible to add multiple query conditions using `&`.

            If you are querying nested parameters, you should flatted the parameter name using the
            `.` operator.

            To query the duration, you can use Duration().

        Example:
            ```python linenums="1"
            import comet_llm
            from comet_llm.query_dsl import TraceMetadata, Duration, Timestamp, UserFeedback

            comet_llm.init()
            api = comet_llm.API()

            # Find all traces where the metadata field `token` is greater than 50
            api.query("workspace", "project", TraceMetadata("token") > 50)

            # Find all traces where the duration field is between 1 second and 2 seconds
            api.query("workspace", "project", (Duration() > 1) & (Duration() <= 2))

            # Find all traces based on the timestamp
            api.query("workspace", "project", Timestamp() > datetime(2023, 9, 10))

            # Find all traces based on positive user feedback
            api.query("workspace", "project", UserFeedback() == 1)
            ```
        """
        matching_api_objects = self._api.query(workspace, project_name, query)

        return [
            llm_trace_api.LLMTraceAPI.__api__from_api_experiment__(api_object)
            for api_object in matching_api_objects
        ]
