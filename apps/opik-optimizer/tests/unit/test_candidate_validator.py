from opik_optimizer_framework.candidate_validator import validate_candidate


class TestCandidateValidator:
    def test_valid_candidate(self):
        config = {"system_prompt": "You are helpful.", "model": "gpt-4"}
        valid, reason = validate_candidate(config, ["system_prompt"])
        assert valid is True
        assert reason is None

    def test_missing_key_rejected(self):
        config = {"model": "gpt-4"}
        valid, reason = validate_candidate(config, ["system_prompt"])
        assert valid is False
        assert reason == "missing_or_empty:system_prompt"

    def test_empty_string_rejected(self):
        config = {"system_prompt": "", "model": "gpt-4"}
        valid, reason = validate_candidate(config, ["system_prompt"])
        assert valid is False
        assert reason == "missing_or_empty:system_prompt"

    def test_multiple_keys_all_present(self):
        config = {"system_prompt": "Be helpful.", "user_message": "Hello", "model": "gpt-4"}
        valid, reason = validate_candidate(config, ["system_prompt", "user_message"])
        assert valid is True
        assert reason is None

    def test_multiple_keys_one_missing(self):
        config = {"system_prompt": "Be helpful.", "model": "gpt-4"}
        valid, reason = validate_candidate(config, ["system_prompt", "user_message"])
        assert valid is False
        assert reason == "missing_or_empty:user_message"

    def test_no_optimizable_keys_always_valid(self):
        config = {"model": "gpt-4"}
        valid, reason = validate_candidate(config, [])
        assert valid is True
        assert reason is None
