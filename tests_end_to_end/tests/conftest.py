import pytest
import os
import opik
import yaml
import json
from opik.configurator.configure import configure
from opik.evaluation import evaluate
from opik.evaluation.metrics import Contains, Equals
from opik import opik_context, track
from playwright.sync_api import Page

from page_objects.ProjectsPage import ProjectsPage
from page_objects.TracesPage import TracesPage
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ExperimentsPage import ExperimentsPage
from tests.sdk_helpers import create_project_sdk, delete_project_by_name_sdk


@pytest.fixture(scope='session', autouse=True)
def configure_local():
    os.environ['OPIK_URL_OVERRIDE'] = "http://localhost:5173/api"
    os.environ['OPIK_WORKSPACE'] = 'default'


@pytest.fixture(scope='session', autouse=True)
def client() -> opik.Opik:
    return opik.Opik(workspace='default', host='http://localhost:5173/api')


@pytest.fixture(scope='function')
def projects_page(page: Page):
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    return projects_page
    

@pytest.fixture(scope='function')
def projects_page_timeout(page: Page) -> ProjectsPage:
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.page.wait_for_timeout(10000)
    return projects_page


@pytest.fixture(scope='function')
def traces_page(page: Page, projects_page, config):
    projects_page.click_project(config['project']['name'])
    traces_page = TracesPage(page)
    return traces_page


@pytest.fixture(scope='function')
def datasets_page(page: Page):
    datasets_page = DatasetsPage(page)
    datasets_page.go_to_page()
    return datasets_page


@pytest.fixture(scope='function')
def experiments_page(page: Page):
    experiments_page = ExperimentsPage(page)
    experiments_page.go_to_page()
    return experiments_page



@pytest.fixture(scope='function')
def create_project_sdk_no_cleanup():
    proj_name = 'projects_crud_tests_sdk'

    create_project_sdk(name=proj_name)
    yield proj_name


@pytest.fixture(scope='function')
def create_project_ui_no_cleanup(page: Page):
    proj_name = 'projects_crud_tests_ui'
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.create_new_project(project_name=proj_name)

    yield proj_name


@pytest.fixture(scope='function')
def create_delete_project_sdk():
    proj_name = 'automated_tests_project'

    create_project_sdk(name=proj_name)
    os.environ['OPIK_PROJECT_NAME'] = proj_name
    yield proj_name
    delete_project_by_name_sdk(name=proj_name)


@pytest.fixture
def create_delete_project_ui(page: Page):
    proj_name = 'automated_tests_project'
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.create_new_project(project_name=proj_name)

    yield proj_name
    delete_project_by_name_sdk(name=proj_name)