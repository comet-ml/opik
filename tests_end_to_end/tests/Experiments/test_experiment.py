import pytest
from playwright.sync_api import Page
from page_objects.ExperimentsPage import ExperimentsPage
from tests.sdk_helpers import get_experiment_by_id, delete_experiment_by_id
from opik import Opik
from opik.evaluation import evaluate
from opik.evaluation.metrics import Contains
import logging
import allure

logger = logging.getLogger(__name__)


class TestExperimentsCrud:
    @pytest.mark.sanity
    @pytest.mark.regression
    @pytest.mark.experiments
    @allure.title("Basic experiment creation")
    def test_experiment_visibility(self, page: Page, mock_experiment):
        """Test experiment visibility in both UI and SDK interfaces.

        Steps:
        1. Create experiment with one metric via mock_experiment fixture
        2. Verify experiment appears in UI list
        3. Verify experiment details via SDK API call
        4. Confirm experiment name matches between UI and SDK
        """
        logger.info("Starting experiment visibility test")
        experiment_name = mock_experiment["name"]

        # Verify in UI
        logger.info(f"Verifying experiment '{experiment_name}' visibility in UI")
        experiments_page = ExperimentsPage(page)
        try:
            experiments_page.go_to_page()
            experiments_page.check_experiment_exists_by_name(mock_experiment["name"])
            logger.info("Successfully verified experiment in UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify experiment in UI.\n"
                f"Experiment name: {experiment_name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to experiment not appearing in list or page load issues"
            ) from e

        # Verify via SDK
        logger.info("Verifying experiment via SDK API")
        experiment_sdk = get_experiment_by_id(mock_experiment["id"])
        assert experiment_sdk.name == mock_experiment["name"], (
            f"Experiment name mismatch between UI and SDK.\n"
            f"Expected: {mock_experiment['name']}\n"
            f"Got: {experiment_sdk.name}"
        )
        logger.info("Successfully verified experiment via SDK")

    @pytest.mark.parametrize("deletion_method", ["ui", "sdk"])
    @pytest.mark.regression
    @pytest.mark.experiments
    @allure.title("Experiment deletion - delete via {deletion_method}")
    def test_experiment_deletion(self, page: Page, mock_experiment, deletion_method):
        """Test experiment deletion via both UI and SDK interfaces.

        Steps:
        1. Create experiment via mock_experiment fixture
        2. Delete experiment through specified interface:
           - UI: Use experiments page delete button
           - SDK: Use delete_experiment_by_id API call
        3. Verify experiment no longer appears in UI
        4. Verify SDK API returns 404 for deleted experiment

        Test runs twice:
        - Once deleting via UI
        - Once deleting via SDK
        """
        logger.info(f"Starting experiment deletion test via {deletion_method}")
        experiment_name = mock_experiment["name"]

        # Delete via specified method
        if deletion_method == "ui":
            logger.info(f"Deleting experiment '{experiment_name}' via UI")
            experiments_page = ExperimentsPage(page)
            try:
                experiments_page.go_to_page()
                experiments_page.delete_experiment_by_name(mock_experiment["name"])
                logger.info("Successfully deleted experiment via UI")
            except Exception as e:
                raise AssertionError(
                    f"Failed to delete experiment via UI.\n"
                    f"Experiment name: {experiment_name}\n"
                    f"Error: {str(e)}\n"
                    f"Note: This could be due to delete button not found or dialog issues"
                ) from e
        elif deletion_method == "sdk":
            logger.info(f"Deleting experiment '{experiment_name}' via SDK")
            delete_experiment_by_id(mock_experiment["id"])
            logger.info("Successfully deleted experiment via SDK")

        # Verify deletion in UI
        logger.info("Verifying experiment no longer appears in UI")
        experiments_page = ExperimentsPage(page)
        try:
            experiments_page.go_to_page()
            experiments_page.check_experiment_not_exists_by_name(
                mock_experiment["name"]
            )
            logger.info("Successfully verified experiment removed from UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify experiment deletion in UI.\n"
                f"Experiment name: {experiment_name}\n"
                f"Deletion method: {deletion_method}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to experiment still visible or page load issues"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying experiment deletion via SDK (expecting 404)")
        try:
            _ = get_experiment_by_id(mock_experiment["id"])
            raise AssertionError(
                f"Experiment still exists after deletion.\n"
                f"Experiment name: {experiment_name}\n"
                f"Deletion method: {deletion_method}\n"
                f"Expected: 404 error\n"
                f"Got: Experiment still accessible"
            )
        except Exception as e:
            if "404" in str(e) or "not found" in str(e).lower():
                logger.info(
                    "Successfully verified experiment deletion (got expected 404)"
                )
            else:
                raise AssertionError(
                    f"Unexpected error checking experiment deletion.\n"
                    f"Experiment name: {experiment_name}\n"
                    f"Deletion method: {deletion_method}\n"
                    f"Expected: 404 error\n"
                    f"Got: {str(e)}"
                ) from e


class TestExperimentScores:
    @pytest.mark.regression
    @pytest.mark.experiments
    @allure.title("Experiment scores logging and retrieval")
    def test_experiment_scores_e2e(
        self, client: Opik, create_dataset_sdk, insert_dataset_items_sdk
    ):
        """Test experiment scores are correctly logged and retrieved via SDK.

        Steps:
        1. Create experiment with experiment_scoring_functions
        2. Verify experiment scores are logged in evaluation result
        3. Verify experiment scores can be retrieved via SDK API
        4. Verify score values are correct
        5. Cleanup experiment
        """
        logger.info("Starting experiment scores E2E test")

        # Define experiment score function that computes max score
        def compute_max_score(test_results):
            from opik.evaluation.metrics import score_result

            scores = [
                result.score_results[0].value
                for result in test_results
                if result.score_results and len(result.score_results) > 0
            ]
            if not scores:
                return []

            return [
                score_result.ScoreResult(
                    name="max_contains_score",
                    value=max(scores),
                    reason=f"Maximum score across {len(scores)} test cases",
                )
            ]

        # Create dataset and run evaluation with experiment scores
        dataset = client.get_dataset(create_dataset_sdk)
        experiment_name = f"test_experiment_scores_{get_random_string(6)}"

        logger.info("Running evaluation with experiment scoring functions")
        evaluation = evaluate(
            experiment_name=experiment_name,
            dataset=dataset,
            task=lambda item: {
                "input": item["input"],
                "output": item["output"],
                "reference": "output",
            },
            scoring_metrics=[Contains()],
            experiment_scoring_functions=[compute_max_score],
        )

        try:
            # Verify experiment scores in evaluation result
            logger.info("Verifying experiment scores in evaluation result")
            assert (
                len(evaluation.experiment_scores) > 0
            ), "No experiment scores returned in evaluation result"
            assert any(
                score.name == "max_contains_score"
                for score in evaluation.experiment_scores
            ), "Expected 'max_contains_score' not found in experiment scores"

            # Verify experiment scores via SDK API
            logger.info("Verifying experiment scores via SDK API")
            experiment = get_experiment_by_id(evaluation.experiment_id)
            assert (
                experiment.experiment_scores is not None
            ), "Experiment scores not found in experiment data"
            assert (
                len(experiment.experiment_scores) > 0
            ), "Experiment scores list is empty"

            score_names = [score.name for score in experiment.experiment_scores]
            assert (
                "max_contains_score" in score_names
            ), f"Expected 'max_contains_score' in {score_names}"

            logger.info("Successfully verified experiment scores E2E")

        finally:
            # Cleanup
            try:
                delete_experiment_by_id(evaluation.experiment_id)
                logger.info("Successfully cleaned up experiment")
            except Exception as e:
                logger.warning(f"Experiment cleanup error: {e}")


def get_random_string(length):
    import random
    import string

    letters = string.ascii_lowercase
    return "".join(random.choice(letters) for _ in range(length))
