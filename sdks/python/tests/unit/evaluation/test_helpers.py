import logging

from opik.evaluation import helpers


class TestResolveProjectName:
    def test_dataset_has_no_project__user_value_used(self, capture_log):
        resolved = helpers.resolve_project_name(
            value_from_dataset=None,
            value_from_user="caller-project",
            caller_name="evaluate",
        )

        assert resolved == "caller-project"
        assert capture_log.records == []

    def test_dataset_has_no_project__user_none__returns_none(self, capture_log):
        resolved = helpers.resolve_project_name(
            value_from_dataset=None,
            value_from_user=None,
            caller_name="evaluate",
        )

        assert resolved is None
        assert capture_log.records == []

    def test_dataset_has_project__user_none__returns_dataset_project__no_warning(
        self, capture_log
    ):
        resolved = helpers.resolve_project_name(
            value_from_dataset="dataset-project",
            value_from_user=None,
            caller_name="evaluate",
        )

        assert resolved == "dataset-project"
        assert capture_log.records == []

    def test_dataset_has_project__user_override__dataset_wins_and_warning_logged(
        self, capture_log
    ):
        resolved = helpers.resolve_project_name(
            value_from_dataset="dataset-project",
            value_from_user="caller-project",
            caller_name="evaluate_prompt",
        )

        assert resolved == "dataset-project"
        warning_records = [
            record
            for record in capture_log.records
            if record.levelno == logging.WARNING
        ]
        assert len(warning_records) == 1
        message = warning_records[0].getMessage()
        assert "deprecated" in message
        assert "evaluate_prompt()" in message
        assert "dataset-project" in message
        assert "caller-project" in message
