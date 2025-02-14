import pytest
from playwright.sync_api import Page, expect
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from Traces.traces_config import PREFIX
from collections import Counter
from sdk_helpers import (
    get_traces_of_project_sdk,
    delete_list_of_traces_sdk,
    wait_for_traces_to_be_visible,
)


class TestTracesCrud:
    @pytest.mark.parametrize("traces_number", [1, 15])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    @pytest.mark.sanity
    def test_trace_creation(
        self, page: Page, traces_number, create_project, create_traces
    ):
        """Testing basic creation of traces via both decorator and low-level client.
        Test case is split into 4, creating 1 and then 15 traces using both the decorator and the client respectively

        1. Create a new project
        2. Create the traces using one of the creation methods, following the naming convention of "test-trace-X", where X is from 1 to 25 (so all have unique names) - no errors should occur
        3. In the UI, check that the presented number of traces in the project matches the number of traces created in the test case
        """
        project_name = create_project
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        _ = create_traces

        traces_created = traces_page.get_total_number_of_traces_in_project()
        assert traces_created == traces_number

    @pytest.mark.parametrize("traces_number", [25])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    def test_traces_visibility(
        self, page: Page, traces_number, create_project, create_traces
    ):
        """
        Testing visibility within the UI and SDK of traces created via both the decorator and the client
        Test case is split into 2, creating traces via decorator first, and then via the low level client

        1. Create a new project
        2. Create 25 traces via either the decorator or the client, following the naming convention of "test-trace-X", where X is from 1 to 25 (so all have unique names)
        3. Check all the traces are visible in the UI:
            - Scroll through all pages in the project and grab every trace name
            - Check that the list of names present in the UI is exactly equal to the list of names of the traces created (exactly the same elements on both sides)
        4. Check all the traces are visible in the SDK:
            - Fetch all traces of the project via the API client (OpikApi.traces.get_traces_by_project)
            - Check that the list of names present in the result is exactly equal to the list of names of the traces created (exactly the same elements on both sides)
        """
        project_name = create_project
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        _ = create_traces

        created_trace_names = Counter([PREFIX + str(i) for i in range(traces_number)])
        traces_ui = traces_page.get_all_trace_names_in_project()
        assert Counter(traces_ui) == created_trace_names

        traces_sdk = get_traces_of_project_sdk(
            project_name=project_name, size=traces_number
        )
        traces_sdk_names = [trace["name"] for trace in traces_sdk]
        assert Counter(traces_sdk_names) == created_trace_names

    @pytest.mark.parametrize("traces_number", [10])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    def test_delete_traces_sdk(
        self, page: Page, traces_number, create_project, create_traces
    ):
        """
        Testing trace deletion via the SDK API client (v1/private/traces/delete endpoint)
        Test case is split into 2, creating traces via the decorator first, then via the client

        1. Create 10 traces via either the decorator or the client, following the naming convention of "test-trace-X", where X is from 1 to 25 (so all have unique names)
        2. Fetch all the newly created trace data via the SDK API client (v1/private/traces endpoint, with project_name parameter)
        3. Delete the first 2 traces in the list via the SDK API client (v1/private/traces/delete endpoint)
        4. Check in the UI that the deleted traces are no longer present in the project page
        5. Check in the SDK that the deleted traces are no longer present in the fetch request (v1/private/traces endpoint, with project_name parameter)
        """
        project_name = create_project
        _ = create_traces

        wait_for_traces_to_be_visible(project_name=project_name, size=traces_number)
        traces_sdk = get_traces_of_project_sdk(
            project_name=project_name, size=traces_number
        )
        traces_sdk_names_ids = [
            {"id": trace["id"], "name": trace["name"]} for trace in traces_sdk
        ]

        traces_to_delete = traces_sdk_names_ids[0:2]
        delete_list_of_traces_sdk(trace["id"] for trace in traces_to_delete)

        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        expect(
            traces_page.page.get_by_role("row", name=traces_to_delete[0]["name"])
        ).not_to_be_visible()
        expect(
            traces_page.page.get_by_role("row", name=traces_to_delete[1]["name"])
        ).not_to_be_visible()

        traces_sdk = get_traces_of_project_sdk(
            project_name=project_name, size=traces_number
        )
        traces_sdk_names = [trace["name"] for trace in traces_sdk]

        assert all(name not in traces_sdk_names for name in traces_to_delete)

    @pytest.mark.parametrize("traces_number", [10])
    @pytest.mark.parametrize(
        "create_traces",
        [
            "log_x_traces_with_one_span_via_decorator",
            "log_x_traces_with_one_span_via_client",
        ],
        indirect=True,
    )
    def test_delete_traces_ui(
        self, page: Page, traces_number, create_project, create_traces
    ):
        """
        Testing trace deletion via the UI
        Test case is split into 2, creating traces via the decorator first, then via the client

        1. Create 10 traces via either the decorator or the client, following the naming convention of "test-trace-X", where X is from 1 to 25 (so all have unique names)
        2. Fetch all the newly created trace data via the SDK API client (v1/private/traces endpoint, with project_name parameter)
        3. Delete the first 2 traces in the list via the UI (selecting them based on name and clicking the Delete button)
        4. Check in the UI that the deleted traces are no longer present in the project page
        5. Check in the SDK that the deleted traces are no longer present in the fetch request (v1/private/traces endpoint, with project_name parameter)
        """
        project_name = create_project
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        _ = create_traces

        traces_sdk = get_traces_of_project_sdk(
            project_name=project_name, size=traces_number
        )
        traces_sdk_names = [trace["name"] for trace in traces_sdk]

        traces_to_delete = traces_sdk_names[0:2]
        traces_page.delete_single_trace_by_name(traces_to_delete[0])
        traces_page.delete_single_trace_by_name(traces_to_delete[1])
        traces_page.page.wait_for_timeout(200)

        expect(
            traces_page.page.get_by_role("row", name=traces_to_delete[0])
        ).not_to_be_visible()
        expect(
            traces_page.page.get_by_role("row", name=traces_to_delete[1])
        ).not_to_be_visible()

        wait_for_traces_to_be_visible(project_name=project_name, size=traces_number)
        traces_sdk = get_traces_of_project_sdk(
            project_name=project_name, size=traces_number
        )
        traces_sdk_names = [trace["name"] for trace in traces_sdk]

        assert all(name not in traces_sdk_names for name in traces_to_delete)
