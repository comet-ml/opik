"""E2E tests for ChatPrompt import/export functionality."""

import json
import os
import subprocess
import tempfile
import uuid
from pathlib import Path
from typing import List
import pytest

import opik
from opik.api_objects.prompt import PromptType
from ..conftest import random_chars


class TestChatPromptImportExport:
    """Test ChatPrompt import/export functionality end-to-end."""

    @pytest.fixture
    def test_data_dir(self):
        """Create a temporary directory for test data."""
        with tempfile.TemporaryDirectory() as temp_dir:
            yield Path(temp_dir)

    @pytest.fixture
    def source_project_name(self, opik_client: opik.Opik):
        """Create a source project for testing."""
        project_name = f"cli-test-chat-source-{random_chars()}"
        yield project_name
        # Cleanup is handled by the test framework

    @pytest.fixture
    def target_project_name(self, opik_client: opik.Opik):
        """Create a target project for testing."""
        project_name = f"cli-test-chat-target-{random_chars()}"
        yield project_name
        # Cleanup is handled by the test framework

    def _create_test_chat_prompt(
        self, opik_client: opik.Opik, project_name: str
    ) -> str:
        """Create a test ChatPrompt."""
        unique_identifier = str(uuid.uuid4())[-6:]
        prompt_name = f"cli-test-chat-prompt-{unique_identifier}"
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {
                "role": "user",
                "content": "Hello, {{name}}! How can I help you with {{topic}}?",
            },
        ]
        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=messages,
            metadata={"version": "1.0", "test": "import_export"},
        )
        return prompt_name

    def _create_test_chat_prompt_with_jinja(
        self, opik_client: opik.Opik, project_name: str
    ) -> str:
        """Create a test ChatPrompt with JINJA2 template type."""
        unique_identifier = str(uuid.uuid4())[-6:]
        prompt_name = f"cli-test-chat-prompt-jinja-{unique_identifier}"
        messages = [
            {"role": "system", "content": "You are a {{ role }}."},
            {
                "role": "user",
                "content": "Hello, {{ name }}! Tell me about {{ topic }}.",
            },
        ]
        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=messages,
            metadata={"version": "2.0", "template": "jinja"},
            type=PromptType.JINJA2,
        )
        return prompt_name

    def _run_cli_command(
        self, cmd: List[str], description: str = ""
    ) -> subprocess.CompletedProcess:
        """Run a CLI command and return the result."""
        # Use the module path to ensure we get the latest code
        full_cmd = ["python", "-m", "opik.cli"] + cmd
        # Set environment to disable rich output for better subprocess capture
        env = {
            **os.environ,
            "TERM": "dumb",  # Disable rich terminal features
            "NO_COLOR": "1",  # Disable colors
        }
        result = subprocess.run(
            full_cmd,
            capture_output=True,
            text=True,
            cwd=Path(__file__).parent.parent,
            env=env,
        )

        if result.returncode != 0:
            print(f"Command failed: {' '.join(full_cmd)}")
            print(f"STDOUT: {result.stdout}")
            print(f"STDERR: {result.stderr}")

        return result

    def test_export_chat_prompt_structure(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test that exported ChatPrompt has correct structure."""
        # Step 1: Create a ChatPrompt
        prompt_name = self._create_test_chat_prompt(opik_client, source_project_name)

        # Step 2: Export the ChatPrompt
        export_cmd = [
            "export",
            "default",
            "prompt",
            prompt_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd, "Export ChatPrompt")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 3: Verify export file exists
        prompts_dir = test_data_dir / "default" / "prompts"
        assert prompts_dir.exists(), f"Export directory not found: {prompts_dir}"

        prompt_files = list(prompts_dir.glob("prompt_*.json"))
        assert (
            len(prompt_files) >= 1
        ), f"Expected prompt files, found: {list(prompts_dir.glob('*'))}"

        # Step 4: Verify exported file structure
        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        # Verify top-level structure
        assert "name" in prompt_data
        assert prompt_data["name"] == prompt_name
        assert "current_version" in prompt_data
        assert "history" in prompt_data
        assert "downloaded_at" in prompt_data

        # Verify current_version structure
        current_version = prompt_data["current_version"]
        assert "prompt" in current_version
        assert "metadata" in current_version
        assert "type" in current_version
        assert "commit" in current_version
        assert "template_structure" in current_version

        # Verify template_structure is "chat"
        assert (
            current_version["template_structure"] == "chat"
        ), f"Expected template_structure to be 'chat', got '{current_version['template_structure']}'"

        # Verify prompt content is a list (not a string)
        prompt_content = current_version["prompt"]
        assert isinstance(
            prompt_content, list
        ), f"Expected prompt to be a list for ChatPrompt, got {type(prompt_content)}"

        # Verify list contains message dictionaries
        assert len(prompt_content) > 0, "Expected at least one message in ChatPrompt"
        for message in prompt_content:
            assert isinstance(
                message, dict
            ), f"Expected message to be a dict, got {type(message)}"
            assert "role" in message, "Expected 'role' key in message"
            assert "content" in message, "Expected 'content' key in message"

        # Verify history entries also have template_structure
        if prompt_data["history"]:
            for history_entry in prompt_data["history"]:
                assert "template_structure" in history_entry
                assert history_entry["template_structure"] == "chat"
                assert isinstance(history_entry["prompt"], list)

    def test_export_chat_prompt_with_jinja(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test exporting ChatPrompt with JINJA2 template type."""
        # Step 1: Create a ChatPrompt with JINJA2
        prompt_name = self._create_test_chat_prompt_with_jinja(
            opik_client, source_project_name
        )

        # Step 2: Export the ChatPrompt
        export_cmd = [
            "export",
            "default",
            "prompt",
            prompt_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd, "Export ChatPrompt with JINJA2")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 3: Verify export
        prompts_dir = test_data_dir / "default" / "prompts"
        prompt_files = list(prompts_dir.glob("prompt_*.json"))

        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        # Verify template type is preserved
        assert prompt_data["current_version"]["type"] == "JINJA2"
        assert prompt_data["current_version"]["template_structure"] == "chat"

    def test_import_chat_prompt(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test importing a ChatPrompt from exported JSON."""
        # Step 1: Create and export a ChatPrompt
        prompt_name = self._create_test_chat_prompt(opik_client, source_project_name)

        export_cmd = [
            "export",
            "default",
            "prompt",
            prompt_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd, "Export ChatPrompt for import test")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 2: Get the original ChatPrompt for comparison
        original_prompt = opik_client.get_chat_prompt(prompt_name)
        original_messages = original_prompt.template

        # Step 3: Delete the original prompt to test import
        # Note: We can't easily delete prompts, so we'll import to a different workspace
        # or verify the import works by checking it doesn't error

        # Step 4: Import the ChatPrompt
        import_cmd = [
            "import",
            "default",
            "prompt",
            str(test_data_dir / "default"),
        ]

        result = self._run_cli_command(import_cmd, "Import ChatPrompt")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Step 5: Verify the imported ChatPrompt
        # Since we can't delete, the import will create a new version or update
        # We can verify it's still accessible and has the correct structure
        imported_prompt = opik_client.get_chat_prompt(prompt_name)
        assert isinstance(
            imported_prompt, opik.ChatPrompt
        ), "Imported prompt should be a ChatPrompt instance"

        # Verify messages match
        imported_messages = imported_prompt.template
        assert len(imported_messages) == len(original_messages)
        for i, (imported_msg, original_msg) in enumerate(
            zip(imported_messages, original_messages)
        ):
            assert imported_msg["role"] == original_msg["role"]
            assert imported_msg["content"] == original_msg["content"]

    def test_export_import_chat_prompt_round_trip(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test complete round-trip: export ChatPrompt then import it."""
        # Step 1: Create a ChatPrompt with specific content
        unique_identifier = str(uuid.uuid4())[-6:]
        prompt_name = f"cli-test-roundtrip-{unique_identifier}"
        original_messages = [
            {"role": "system", "content": "You are a {{ assistant_type }}."},
            {
                "role": "user",
                "content": "Hello! I need help with {{ task }}.",
            },
            {
                "role": "assistant",
                "content": "I'll help you with {{ task }}. Let me start by...",
            },
        ]
        original_metadata = {"test": "roundtrip", "version": "1.0"}

        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=original_messages,
            metadata=original_metadata,
            type=PromptType.MUSTACHE,
        )

        # Step 2: Export the ChatPrompt
        export_cmd = [
            "export",
            "default",
            "prompt",
            prompt_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd, "Export for round-trip test")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 3: Verify export structure
        prompts_dir = test_data_dir / "default" / "prompts"
        prompt_files = list(prompts_dir.glob("prompt_*.json"))
        assert len(prompt_files) == 1, "Expected exactly one prompt file"

        with open(prompt_files[0], "r") as f:
            exported_data = json.load(f)

        # Verify exported data structure
        assert exported_data["name"] == prompt_name
        assert exported_data["current_version"]["template_structure"] == "chat"
        exported_messages = exported_data["current_version"]["prompt"]
        assert isinstance(exported_messages, list)
        assert len(exported_messages) == len(original_messages)

        # Step 4: Import the ChatPrompt
        import_cmd = [
            "import",
            "default",
            "prompt",
            str(test_data_dir / "default"),
        ]

        result = self._run_cli_command(import_cmd, "Import for round-trip test")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Step 5: Verify imported ChatPrompt matches original
        imported_prompt = opik_client.get_chat_prompt(prompt_name)
        assert isinstance(imported_prompt, opik.ChatPrompt)

        imported_messages = imported_prompt.template
        assert len(imported_messages) == len(original_messages)

        # Compare each message
        for imported_msg, original_msg in zip(imported_messages, original_messages):
            assert imported_msg["role"] == original_msg["role"]
            assert imported_msg["content"] == original_msg["content"]

        # Verify metadata (may have additional fields from backend)
        imported_metadata = imported_prompt.metadata
        if imported_metadata and original_metadata:
            for key, value in original_metadata.items():
                assert (
                    key in imported_metadata
                ), f"Metadata key '{key}' missing in imported prompt"
                assert (
                    imported_metadata[key] == value
                ), f"Metadata value for '{key}' doesn't match"

    def test_export_import_chat_prompt_with_history(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test exporting and importing ChatPrompt with version history."""
        # Step 1: Create a ChatPrompt with multiple versions
        unique_identifier = str(uuid.uuid4())[-6:]
        prompt_name = f"cli-test-history-{unique_identifier}"

        # Create version 1
        messages_v1 = [
            {"role": "system", "content": "You are helpful."},
            {"role": "user", "content": "Hi!"},
        ]
        opik_client.create_chat_prompt(name=prompt_name, messages=messages_v1)

        # Create version 2
        messages_v2 = [
            {"role": "system", "content": "You are very helpful."},
            {"role": "user", "content": "Hello!"},
            {"role": "assistant", "content": "How can I help?"},
        ]
        opik_client.create_chat_prompt(name=prompt_name, messages=messages_v2)

        # Step 2: Export the ChatPrompt
        export_cmd = [
            "export",
            "default",
            "prompt",
            prompt_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd, "Export ChatPrompt with history")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 3: Verify export includes history
        prompts_dir = test_data_dir / "default" / "prompts"
        prompt_files = list(prompts_dir.glob("prompt_*.json"))

        with open(prompt_files[0], "r") as f:
            exported_data = json.load(f)

        # Verify history exists
        assert "history" in exported_data
        assert len(exported_data["history"]) >= 1, "Expected at least one history entry"

        # Verify each history entry has template_structure
        for history_entry in exported_data["history"]:
            assert "template_structure" in history_entry
            assert history_entry["template_structure"] == "chat"
            assert isinstance(history_entry["prompt"], list)

        # Step 4: Import and verify
        import_cmd = [
            "import",
            "default",
            "prompt",
            str(test_data_dir / "default"),
        ]

        result = self._run_cli_command(import_cmd, "Import ChatPrompt with history")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Verify the imported prompt is still a ChatPrompt
        imported_prompt = opik_client.get_chat_prompt(prompt_name)
        assert isinstance(imported_prompt, opik.ChatPrompt)

    def test_export_chat_prompt_vs_text_prompt(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ):
        """Test that ChatPrompt and text Prompt export differently."""
        # Step 1: Create both types of prompts
        chat_prompt_name = self._create_test_chat_prompt(
            opik_client, source_project_name
        )

        text_prompt_name = f"cli-test-text-prompt-{random_chars()}"
        opik_client.create_prompt(
            name=text_prompt_name,
            prompt="You are a helpful assistant. Answer: {{ question }}",
        )

        # Step 2: Export both
        export_cmd = [
            "export",
            "default",
            "prompt",
            chat_prompt_name,
            "--path",
            str(test_data_dir),
        ]
        result = self._run_cli_command(export_cmd, "Export ChatPrompt")
        assert result.returncode == 0

        export_cmd = [
            "export",
            "default",
            "prompt",
            text_prompt_name,
            "--path",
            str(test_data_dir),
        ]
        result = self._run_cli_command(export_cmd, "Export text Prompt")
        assert result.returncode == 0

        # Step 3: Verify differences
        prompts_dir = test_data_dir / "default" / "prompts"
        prompt_files = list(prompts_dir.glob("prompt_*.json"))

        chat_file = None
        text_file = None

        for prompt_file in prompt_files:
            with open(prompt_file, "r") as f:
                data = json.load(f)
                if data["name"] == chat_prompt_name:
                    chat_file = data
                elif data["name"] == text_prompt_name:
                    text_file = data

        assert chat_file is not None, "ChatPrompt export file not found"
        assert text_file is not None, "Text Prompt export file not found"

        # Verify template_structure difference
        assert (
            chat_file["current_version"]["template_structure"] == "chat"
        ), "ChatPrompt should have template_structure='chat'"
        assert (
            text_file["current_version"].get("template_structure", "text") == "text"
        ), "Text Prompt should have template_structure='text' (or missing, defaulting to text)"

        # Verify prompt content type difference
        chat_content = chat_file["current_version"]["prompt"]
        text_content = text_file["current_version"]["prompt"]

        assert isinstance(chat_content, list), "ChatPrompt prompt should be a list"
        assert isinstance(text_content, str), "Text Prompt prompt should be a string"
