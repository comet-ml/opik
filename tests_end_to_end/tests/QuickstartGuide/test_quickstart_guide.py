import subprocess
import tempfile
from pathlib import Path
import pytest
import logging
import allure

logger = logging.getLogger(__name__)


# TODO
# unskip bedrock once AWS credentials set up in automation repo
# unskip Haystack once fix to snippet is deployed to prod
@pytest.mark.parametrize(
    "integration",
    [
        "Function decorators",
        "OpenAI",
        "Anthropic",
        # "Bedrock",
        "Gemini",
        "LangChain",
        "LangGraph",
        "LlamaIndex",
        # "Haystack",
        "LiteLLM",
        "Ragas",
        "Groq",
        "DSPy",
    ],
)
@allure.title("Test Quickstart Snippet - {integration}")
def test_quickstart_snippet(page, env_config, integration):
    """
    Test that:
    1. Opens the Opik homepage
    2. Clicks the "Quickstart guide" button
    3. Selects OpenAI from the integration options
    4. Extracts the code snippet
    5. Saves it to a temporary file
    6. Runs the code
    7. Shows the output
    """

    page.goto(env_config.base_url)
    page.wait_for_load_state("networkidle")

    logger.info("Clicking quickstart")
    quickstart_button = page.get_by_text("Quickstart guide")
    quickstart_button.click()

    page.wait_for_selector(".cm-content")

    logger.info(f"Clicking {integration}")
    openai_option = page.locator("li", has_text=integration)
    openai_option.click()

    page.wait_for_selector(".cm-content")

    page.get_by_role("button").nth(2).click()
    code = page.evaluate("navigator.clipboard.readText()")

    # Create a temporary directory for test files
    with tempfile.TemporaryDirectory() as temp_dir:
        file_path = Path(temp_dir) / "quickstart_test.py"
        with open(file_path, "w") as f:
            f.write(code)

        logger.info(f"Saved code snippet to: {file_path}")

        # Run the code snippet
        logger.info(f"Running the {integration} code")
        try:
            # Set a timeout for the subprocess to prevent hanging
            result = subprocess.run(
                ["python", str(file_path)], capture_output=True, text=True, timeout=60
            )

            logger.info("\n=== Output ===")
            if result.stdout:
                logger.info("STDOUT:")
                logger.info(result.stdout)

            if result.stderr:
                logger.info("STDERR:")
                logger.info(result.stderr)

            if result.returncode == 0:
                logger.info("\nCode executed successfully!")
            else:
                logger.info(
                    f"\nCode execution failed with return code: {result.returncode}"
                )
                raise AssertionError(f"code failed with error {result.stderr}")

        except subprocess.TimeoutExpired:
            raise AssertionError("Code execution timed out after 30 seconds")
