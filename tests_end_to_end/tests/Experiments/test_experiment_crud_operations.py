import pytest
from playwright.sync_api import Page
from page_objects.ExperimentsPage import ExperimentsPage
from sdk_helpers import get_experiment_by_id, delete_experiment_by_id


class TestExperimentsCrud:
    @pytest.mark.sanity
    def test_experiment_visibility(self, page: Page, mock_experiment):
        """
        Tests experiment creation and visibility of experiment in both UI and SDK
        1. Create an experiment with one metric on an arbitrary dataset(mock_experiment fixture)
        2. Check the experiment is visible in the UI and fetchable via the API (v1/private/experiments/<experiment id>)
        """
        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()

        experiments_page.check_experiment_exists_by_name(mock_experiment["name"])

        experiment_sdk = get_experiment_by_id(mock_experiment["id"])
        assert experiment_sdk.name == mock_experiment["name"]

    @pytest.mark.parametrize("deletion_method", ["ui", "sdk"])
    def test_experiment_deletion(self, page: Page, mock_experiment, deletion_method):
        """
        Tests deletion of experiment via both the UI and the SDK and checks experiment correctly no longer appears
        1. Create an experiment with evaluate() function
        2. Delete the experiment via either the UI or the SDK (2 separate test entitites)
        3. Check the experiment does not appear in the UI and that requesting it via the API correctly returns a 404
        """
        if deletion_method == "ui":
            experiments_page = ExperimentsPage(page)
            experiments_page.go_to_page()
            experiments_page.delete_experiment_by_name(mock_experiment["name"])
        elif deletion_method == "sdk":
            delete_experiment_by_id(mock_experiment["id"])

        experiments_page = ExperimentsPage(page)
        experiments_page.go_to_page()
        experiments_page.check_experiment_not_exists_by_name(mock_experiment["name"])

        try:
            _ = get_experiment_by_id(mock_experiment["id"])
            assert False, f"experiment {mock_experiment['name']} somehow still exists after deletion"
        except Exception as e:
            if "404" in str(e) or "not found" in str(e).lower():
                pass
            else:
                raise
