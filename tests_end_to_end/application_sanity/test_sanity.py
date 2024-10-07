import pytest
from playwright.sync_api import Page, expect
from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.TracesPageSpansMenu import TracesPageSpansMenu


def test_project_name(page: Page, log_traces_and_spans_decorator, log_traces_and_spans_low_level):
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.check_project_exists('test-project')


def test_traces_created(page, config, log_traces_and_spans_low_level, log_traces_and_spans_decorator):
    #navigate to project
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    
    #wait for data to actually arrive to the frontend
    #TODO: replace this with a smarter waiting mechanism
    page.wait_for_timeout(5000)
    projects_page.click_project(config['project']['name'])

    #grab all traces of project
    traces_page = TracesPage(page)
    trace_names = traces_page.get_all_trace_names()

    client_prefix = config['traces']['client']['prefix']
    decorator_prefix = config['traces']['decorator']['prefix']

    for count in range(config['traces']['count']):
        for prefix in [client_prefix, decorator_prefix]:
            assert prefix+str(count) in trace_names


def test_spans_of_traces(page, config, log_traces_and_spans_low_level, log_traces_and_spans_decorator):
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    
    #wait for data to actually arrive to the frontend
    #TODO: replace this with a smarter waiting mechanism
    projects_page.click_project(config['project']['name'])

    #grab all traces of project
    traces_page = TracesPage(page)
    trace_names = traces_page.get_all_trace_names()

    for trace in trace_names:
        page.get_by_text(trace).click()
        spans_menu = TracesPageSpansMenu(page)
        trace_type = trace.split('-')[0] # 'client' or 'decorator'
        for count in range(config['spans']['count']):
            prefix = config['spans'][trace_type]['prefix']
            spans_menu.check_span_exists_by_name(f'{prefix}{count}')


