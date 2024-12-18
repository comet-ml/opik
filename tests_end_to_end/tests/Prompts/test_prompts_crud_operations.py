import pytest
import opik
from time import sleep
from playwright.sync_api import Page
from page_objects.PromptLibraryPage import PromptLibraryPage
from page_objects.PromptPage import PromptPage

class TestPromptsCrud:
    @pytest.mark.parametrize('prompt_fixture', ['create_prompt_sdk', 'create_prompt_ui'])
    def test_prompt_visibility(self, request, page: Page, client: opik.Opik, prompt_fixture):
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)
        
        retries = 0
        while retries < 5:
            prompt_sdk: opik.Prompt = client.get_prompt(name=prompt.name)
            if prompt_sdk:
                break
            else:
                sleep(1)
        assert prompt.name == prompt_sdk.name
        assert prompt.prompt == prompt_sdk.prompt

        prompt_library_page = PromptLibraryPage(page)
        prompt_library_page.go_to_page()
        prompt_library_page.check_prompt_exists_in_workspace(prompt_name=prompt.name)

    
    @pytest.mark.parametrize('prompt_fixture', ['create_prompt_sdk', 'create_prompt_ui'])
    def test_prompt_deletion(self, request, page: Page, client: opik.Opik, prompt_fixture):
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)

        prompt_library_page = PromptLibraryPage(page)
        prompt_library_page.go_to_page()
        prompt_library_page.delete_prompt_by_name(prompt.name)

        prompt_library_page.page.reload()
        prompt_library_page.check_prompt_not_exists_in_workspace(prompt_name=prompt.name)

        assert not client.get_prompt(name=prompt.name)


    @pytest.mark.parametrize('prompt_fixture', ['create_prompt_sdk', 'create_prompt_ui'])
    @pytest.mark.parametrize('update_method', ['sdk', 'ui'])
    def test_prompt_update(self, request, page: Page, client: opik.Opik, prompt_fixture, update_method):
        prompt: opik.Prompt = request.getfixturevalue(prompt_fixture)
        UPDATE_TEXT = 'This is an updated prompt version'

        prompt_library_page = PromptLibraryPage(page)
        prompt_library_page.go_to_page()
        prompt_library_page.click_prompt(prompt_name=prompt.name)

        prompt_page = PromptPage(page)

        if update_method == 'sdk':
            _ = opik.Prompt(name=prompt.name, prompt=UPDATE_TEXT)
        elif update_method == 'ui':
            prompt_page.edit_prompt(new_prompt=UPDATE_TEXT)

        versions = prompt_page.get_all_prompt_versions_with_commit_ids_for_prompt()
        assert prompt.prompt in versions.keys()
        assert UPDATE_TEXT in versions.keys()

        prompt_page.click_most_recent_commit()
        assert prompt_page.get_prompt_of_selected_commit() == UPDATE_TEXT

        prompt_update = client.get_prompt(name=prompt.name)
        assert prompt_update.prompt == UPDATE_TEXT
        original_commit_id = versions[prompt.prompt]
        assert client.get_prompt(name=prompt.name, commit=original_commit_id).prompt == prompt.prompt



        


        