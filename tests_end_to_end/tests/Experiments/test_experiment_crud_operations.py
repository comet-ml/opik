import pytest
from playwright.sync_api import Page, expect
from page_objects.DatasetsPage import DatasetsPage
from page_objects.ExperimentsPage import ExperimentsPage
from sdk_helpers import get_experiment_by_id, delete_experiment_by_id
import opik
import time


class TestExperimentsCrud:
    
    def test_experiment_visibility(self, page: Page, mock_experiment):
        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()

        experiments_page.check_experiment_exists_by_name(mock_experiment['name'])

        experiment_sdk = get_experiment_by_id(mock_experiment['id'])
        assert experiment_sdk.name == mock_experiment['name']

    
    @pytest.mark.parametrize('deletion_method', ['ui', 'sdk'])
    def test_experiment_deletion(self, page: Page, mock_experiment, deletion_method):
        if deletion_method == 'ui':
            experiments_page = ExperimentsPage(page)
            experiments_page.go_to_page()
            experiments_page.delete_experiment_by_name(mock_experiment['name'])
        elif deletion_method == 'sdk':
            delete_experiment_by_id(mock_experiment['id'])

        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()
        experiments_page.check_experiment_not_exists_by_name(mock_experiment['name'])

        try:
            _ = get_experiment_by_id(mock_experiment['id'])
            assert False, f'experiment {mock_experiment['name']} somehow still exists after deletion'
        except Exception as e:
            if '404' in str(e) or 'not found' in str(e).lower():
                pass
            else:
                raise

        