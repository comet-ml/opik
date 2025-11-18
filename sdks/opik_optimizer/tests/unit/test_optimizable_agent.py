import os
from unittest.mock import Mock


from opik_optimizer.optimizable_agent import OptimizableAgent
import opik_optimizer


class TestOptimizableAgent:
    """Test cases for OptimizableAgent class."""

    def test_opik_project_name_not_overwritten_when_already_set(self) -> None:
        """Test that OPIK_PROJECT_NAME is not overwritten when already set in environment."""
        # Set up test data
        original_project_name = "Existing Project"
        agent_project_name = "Agent Project"

        # Create a mock prompt
        mock_prompt = Mock(spec=opik_optimizer.ChatPrompt)

        # Set up the environment variable before agent initialization
        os.environ["OPIK_PROJECT_NAME"] = original_project_name

        try:
            # Create agent instance with a different project name
            agent = OptimizableAgent(prompt=mock_prompt)
            agent.project_name = agent_project_name

            # Re-initialize the LLM to trigger the environment check
            agent.init_llm()

            # Verify that the environment variable was not overwritten
            assert os.environ["OPIK_PROJECT_NAME"] == original_project_name
            assert os.environ["OPIK_PROJECT_NAME"] != agent_project_name

        finally:
            # Clean up the environment variable
            if "OPIK_PROJECT_NAME" in os.environ:
                del os.environ["OPIK_PROJECT_NAME"]

    def test_opik_project_name_set_when_not_in_environment(self) -> None:
        """Test that OPIK_PROJECT_NAME is set when not already in environment."""
        # Create a mock prompt
        mock_prompt = Mock(spec=opik_optimizer.ChatPrompt)

        # Ensure the environment variable is not set
        if "OPIK_PROJECT_NAME" in os.environ:
            del os.environ["OPIK_PROJECT_NAME"]

        try:
            # Create agent instance with custom project name
            agent = OptimizableAgent(prompt=mock_prompt)
            agent.project_name = "Test Project"

            # Clear the environment variable again after initialization
            if "OPIK_PROJECT_NAME" in os.environ:
                del os.environ["OPIK_PROJECT_NAME"]

            # Re-initialize the LLM to trigger the environment check
            agent.init_llm()

            # Verify that the environment variable was set
            assert "OPIK_PROJECT_NAME" in os.environ
            assert os.environ["OPIK_PROJECT_NAME"] == "Test Project"

        finally:
            # Clean up the environment variable
            if "OPIK_PROJECT_NAME" in os.environ:
                del os.environ["OPIK_PROJECT_NAME"]

    def test_opik_project_name_with_default_project_name(self) -> None:
        """Test that OPIK_PROJECT_NAME is set with default project name when not in environment."""
        # Create a mock prompt
        mock_prompt = Mock(spec=opik_optimizer.ChatPrompt)

        # Ensure the environment variable is not set
        if "OPIK_PROJECT_NAME" in os.environ:
            del os.environ["OPIK_PROJECT_NAME"]

        try:
            # Create agent instance with default project name
            agent = OptimizableAgent(prompt=mock_prompt)

            # Re-initialize the LLM to trigger the environment check
            agent.init_llm()

            # Verify that the environment variable was set with default value
            assert "OPIK_PROJECT_NAME" in os.environ
            assert os.environ["OPIK_PROJECT_NAME"] == "Default Project"

        finally:
            # Clean up the environment variable
            if "OPIK_PROJECT_NAME" in os.environ:
                del os.environ["OPIK_PROJECT_NAME"]
