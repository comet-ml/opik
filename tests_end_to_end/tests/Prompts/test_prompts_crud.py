import pytest
import opik
from time import sleep
from playwright.sync_api import Page
from page_objects.PromptLibraryPage import PromptLibraryPage
from page_objects.PromptPage import PromptPage
import logging
import allure

logger = logging.getLogger(__name__)


class TestPromptsCrud:
    @pytest.mark.parametrize(
        "prompt_fixture", ["create_prompt_sdk", "create_prompt_ui"]
    )
    @allure.id("P1")
    @allure.title("Basic prompt creation - {prompt_fixture}")
    def test_prompt_visibility(
        self, request, page: Page, client: opik.Opik, prompt_fixture
    ):
        """Test prompt visibility in both UI and SDK interfaces.

        Steps:
        1. Create prompt via fixture (runs twice: SDK and UI created prompts)
        2. Verify via SDK:
           - Prompt can be retrieved
           - Name matches creation
           - Text matches creation
        3. Verify via UI:
           - Prompt appears in library
           - Details match creation
        """
        logger.info("Starting prompt visibility test")
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)

        # Verify via SDK with retries
        logger.info(f"Verifying prompt '{prompt.name}' via SDK")
        try:
            retries = 0
            while retries < 5:
                prompt_sdk: opik.Prompt = client.get_prompt(name=prompt.name)
                if prompt_sdk:
                    break
                else:
                    logger.info(f"Prompt not found, retry {retries + 1}/5")
                    sleep(1)
                    retries += 1

            assert prompt.name == prompt_sdk.name, (
                f"Prompt name mismatch in SDK.\n"
                f"Expected: {prompt.name}\n"
                f"Got: {prompt_sdk.name}"
            )
            assert prompt.prompt == prompt_sdk.prompt, (
                f"Prompt text mismatch in SDK.\n"
                f"Expected: {prompt.prompt}\n"
                f"Got: {prompt_sdk.prompt}"
            )
            logger.info("Successfully verified prompt via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify prompt via SDK.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify via UI
        logger.info("Verifying prompt in UI")
        prompt_library_page = PromptLibraryPage(page)
        try:
            prompt_library_page.go_to_page()
            prompt_library_page.check_prompt_exists_in_workspace(
                prompt_name=prompt.name
            )
            logger.info("Successfully verified prompt in UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify prompt in UI.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to prompt not found in library"
            ) from e

    @pytest.mark.parametrize(
        "prompt_fixture", ["create_prompt_sdk", "create_prompt_ui"]
    )
    @allure.id("P2")
    @allure.title("Prompt deletion via UI - {prompt_fixture}")
    def test_prompt_deletion(
        self, request, page: Page, client: opik.Opik, prompt_fixture
    ):
        """Test prompt deletion through UI interface.

        Steps:
        1. Create prompt via fixture (runs twice: SDK and UI created prompts)
        2. Delete prompt through UI
        3. Verify:
           - Prompt no longer appears in UI library
           - Prompt no longer accessible via SDK
        """
        logger.info("Starting prompt deletion test")
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)

        # Delete via UI
        logger.info(f"Deleting prompt '{prompt.name}' via UI")
        prompt_library_page = PromptLibraryPage(page)
        try:
            prompt_library_page.go_to_page()
            prompt_library_page.delete_prompt_by_name(prompt.name)
            logger.info("Successfully deleted prompt via UI")
        except Exception as e:
            raise AssertionError(
                f"Failed to delete prompt via UI.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}\n"
                f"Note: This could be due to delete button not found or dialog issues"
            ) from e

        # Verify deletion in UI
        logger.info("Verifying prompt removed from UI")
        try:
            prompt_library_page.page.reload()
            prompt_library_page.check_prompt_not_exists_in_workspace(
                prompt_name=prompt.name
            )
            logger.info("Successfully verified prompt not visible in UI")
        except Exception as e:
            raise AssertionError(
                f"Prompt still visible in UI after deletion.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify deletion via SDK
        logger.info("Verifying prompt deletion via SDK")
        try:
            result = client.get_prompt(name=prompt.name)
            assert not result, (
                f"Prompt still exists after deletion.\n"
                f"Prompt name: {prompt.name}\n"
                f"SDK result: {result}"
            )
            logger.info("Successfully verified prompt deletion via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify prompt deletion via SDK.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e

    @pytest.mark.parametrize(
        "prompt_fixture", ["create_prompt_sdk", "create_prompt_ui"]
    )
    @pytest.mark.parametrize("update_method", ["sdk", "ui"])
    @allure.id("P3")
    @allure.title("Prompt update - {prompt_fixture} and update via {update_method}")
    def test_prompt_update(
        self, request, page: Page, client: opik.Opik, prompt_fixture, update_method
    ):
        """Test prompt version updates via both UI and SDK interfaces.

        Steps:
        1. Create prompt via fixture (runs for SDK and UI created prompts)
        2. Update prompt text via specified method (runs for SDK and UI updates)
        3. Verify in UI:
           - Both versions appear in Commits tab
           - Most recent commit shows updated text
        4. Verify in SDK:
           - Default fetch returns latest version
           - Can fetch original version by commit ID
        """
        logger.info("Starting prompt update test")
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)
        UPDATE_TEXT = "This is an updated prompt version"

        # Navigate to prompt page
        logger.info(f"Navigating to prompt '{prompt.name}' page")
        prompt_library_page = PromptLibraryPage(page)
        try:
            prompt_library_page.go_to_page()
            prompt_library_page.click_prompt(prompt_name=prompt.name)
            logger.info("Successfully navigated to prompt page")
        except Exception as e:
            raise AssertionError(
                f"Failed to navigate to prompt page.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Update prompt
        prompt_page = PromptPage(page)
        logger.info(f"Updating prompt via {update_method}")
        try:
            if update_method == "sdk":
                _ = opik.Prompt(name=prompt.name, prompt=UPDATE_TEXT)
            elif update_method == "ui":
                prompt_page.edit_prompt(new_prompt=UPDATE_TEXT)
            logger.info("Successfully updated prompt")
        except Exception as e:
            raise AssertionError(
                f"Failed to update prompt via {update_method}.\n"
                f"Prompt name: {prompt.name}\n"
                f"New text: {UPDATE_TEXT}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify versions in UI
        logger.info("Verifying prompt versions in UI")
        try:
            versions = prompt_page.get_all_prompt_versions_with_commit_ids_for_prompt()
            assert prompt.prompt in versions.keys(), (
                f"Original version not found in commits.\n"
                f"Expected text: {prompt.prompt}\n"
                f"Found versions: {list(versions.keys())}"
            )
            assert UPDATE_TEXT in versions.keys(), (
                f"Updated version not found in commits.\n"
                f"Expected text: {UPDATE_TEXT}\n"
                f"Found versions: {list(versions.keys())}"
            )
            logger.info("Successfully verified both versions in commits")

            # Verify most recent version
            prompt_page.click_most_recent_commit()
            current_text = prompt_page.get_prompt_of_selected_commit()
            assert current_text == UPDATE_TEXT, (
                f"Most recent commit shows wrong version.\n"
                f"Expected: {UPDATE_TEXT}\n"
                f"Got: {current_text}"
            )
            logger.info("Successfully verified most recent version")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify prompt versions in UI.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e

        # Verify versions via SDK
        logger.info("Verifying prompt versions via SDK")
        try:
            # Verify latest version
            prompt_update = client.get_prompt(name=prompt.name)
            assert prompt_update.prompt == UPDATE_TEXT, (
                f"Latest version mismatch in SDK.\n"
                f"Expected: {UPDATE_TEXT}\n"
                f"Got: {prompt_update.prompt}"
            )
            logger.info("Successfully verified latest version via SDK")

            # Verify original version
            original_commit_id = versions[prompt.prompt]
            original_version = client.get_prompt(
                name=prompt.name, commit=original_commit_id
            )
            assert original_version.prompt == prompt.prompt, (
                f"Original version mismatch in SDK.\n"
                f"Expected: {prompt.prompt}\n"
                f"Got: {original_version.prompt}"
            )
            logger.info("Successfully verified original version via SDK")
        except Exception as e:
            raise AssertionError(
                f"Failed to verify prompt versions via SDK.\n"
                f"Prompt name: {prompt.name}\n"
                f"Error: {str(e)}"
            ) from e
