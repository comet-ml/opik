import sys
from unittest.mock import patch

from opik.configurator.interactive_helpers import ask_user_for_approval, is_interactive


class TestIsInteractive:
    @patch(
        "opik.configurator.interactive_helpers._in_colab_environment",
        return_value=False,
    )
    @patch(
        "opik.configurator.interactive_helpers._in_ipython_environment",
        return_value=False,
    )
    @patch(
        "opik.configurator.interactive_helpers._in_jupyter_environment",
        return_value=False,
    )
    @patch.object(sys.stdin, "isatty", return_value=True)
    def test_is_interactive__true(
        self,
        isatty,
        _in_jupyter_environment,
        _in_ipython_environment,
        _in_colab_environment,
    ):
        assert is_interactive() is True

    @patch(
        "opik.configurator.interactive_helpers._in_colab_environment",
        return_value=False,
    )
    @patch(
        "opik.configurator.interactive_helpers._in_ipython_environment",
        return_value=False,
    )
    @patch(
        "opik.configurator.interactive_helpers._in_jupyter_environment",
        return_value=False,
    )
    @patch.object(sys.stdin, "isatty", return_value=False)
    def test_is_interactive__false(
        self,
        isatty,
        _in_jupyter_environment,
        _in_ipython_environment,
        _in_colab_environment,
    ):
        assert is_interactive() is False


class TestAskUserForApproval:
    @patch("builtins.input", return_value="Y")
    def test_user_approves_with_y(self, mock_input):
        """
        Test that 'Y' returns True for approval.
        """
        result = ask_user_for_approval("Do you approve?")
        assert result is True

    @patch("builtins.input", return_value="YES")
    def test_user_approves_with_yes(self, mock_input):
        """
        Test that 'YES' returns True for approval.
        """
        result = ask_user_for_approval("Do you approve?")
        assert result is True

    @patch("builtins.input", return_value="")
    def test_user_approves_with_empty_input(self, mock_input):
        """
        Test that empty input returns True for approval.
        """
        result = ask_user_for_approval("Do you approve?")
        assert result is True

    @patch("builtins.input", return_value="N")
    def test_user_disapproves_with_n(self, mock_input):
        """
        Test that 'N' returns False for disapproval.
        """
        result = ask_user_for_approval("Do you disapprove?")
        assert result is False

    @patch("builtins.input", return_value="NO")
    def test_user_disapproves_with_no(self, mock_input):
        """
        Test that 'NO' returns False for disapproval.
        """
        result = ask_user_for_approval("Do you disapprove?")
        assert result is False

    @patch("builtins.input", side_effect=["INVALID", "Y"])
    @patch("opik.configurator.interactive_helpers.LOGGER.error")
    def test_user_enters_invalid_choice_then_approves(
        self, mock_logger_error, mock_input
    ):
        """
        Test that invalid input triggers error logging and prompts again until valid input is entered.
        """
        result = ask_user_for_approval("Do you approve?")
        assert result is True
        mock_logger_error.assert_called_once_with("Wrong choice. Please try again.")

    @patch("builtins.input", side_effect=["INVALID", "NO"])
    @patch("opik.configurator.interactive_helpers.LOGGER.error")
    def test_user_enters_invalid_choice_then_disapproves(
        self, mock_logger_error, mock_input
    ):
        """
        Test that invalid input triggers error logging and prompts again until valid input is entered.
        """
        result = ask_user_for_approval("Do you disapprove?")
        assert result is False
        mock_logger_error.assert_called_once_with("Wrong choice. Please try again.")
