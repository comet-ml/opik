import pytest
from playwright.sync_api import Page
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from traces_config import PREFIX

class TestTracesCrud:
    
    @pytest.mark.parametrize('traces_number', [1, 15])
    @pytest.mark.parametrize('create_traces', ['log_x_traces_with_one_span_via_decorator', 'log_x_traces_with_one_span_via_client'], indirect=True)
    def test_trace_creation_via_decorator(self, page: Page, traces_number, create_delete_project_sdk, create_traces):
        project_name = create_delete_project_sdk
        projects_page = ProjectsPage(page)
        projects_page.go_to_page()
        projects_page.click_project(project_name)
        traces_page = TracesPage(page)
        _ = create_traces
        
        try:
            traces_created = traces_page.get_total_number_of_traces_in_project()
            assert traces_created == traces_number

        except Exception as e:
            print(f'exception occured during trace creation: {e}')
            raise
        
        finally:
            traces_page.delete_all_traces_that_match_name_contains_filter(PREFIX)