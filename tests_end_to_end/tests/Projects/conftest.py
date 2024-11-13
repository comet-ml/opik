import pytest
import os
from typing import Optional

import opik
from playwright.sync_api import Page
from opik.rest_api.client import OpikApi

from sdk_helpers import create_project_sdk, delete_project_by_name_sdk
from page_objects.ProjectsPage import ProjectsPage


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
    proj_name = 'projects_crud_tests_sdk'

    create_project_sdk(name=proj_name)
    yield proj_name
    delete_project_by_name_sdk(name=proj_name)


@pytest.fixture
def create_delete_project_ui(page: Page):
    proj_name = 'projects_crud_tests_ui'
    projects_page = ProjectsPage(page)
    projects_page.go_to_page()
    projects_page.create_new_project(project_name=proj_name)

    yield proj_name
    delete_project_by_name_sdk(name=proj_name)