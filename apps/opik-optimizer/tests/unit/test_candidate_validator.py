from opik_optimizer_framework.candidate_validator import validate_candidate


class TestCandidateValidator:
    def test_valid_candidate(self, sample_candidate_config, sample_optimization_state):
        valid, reason = validate_candidate(sample_candidate_config, sample_optimization_state)
        assert valid is True
        assert reason is None

    def test_empty_messages_rejected(self, sample_optimization_state):
        config = {"prompt_messages": [], "model": "gpt-4"}
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert reason == "empty_messages"

    def test_missing_role_rejected(self, sample_optimization_state):
        config = {
            "prompt_messages": [{"content": "hi"}],
            "model": "gpt-4",
        }
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "missing_role_or_content" in reason

    def test_missing_content_rejected(self, sample_optimization_state):
        config = {
            "prompt_messages": [{"role": "user"}],
            "model": "gpt-4",
        }
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "missing_role_or_content" in reason

    def test_empty_role_rejected(self, sample_optimization_state):
        config = {
            "prompt_messages": [{"role": "", "content": "hi"}],
            "model": "gpt-4",
        }
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "empty_role_or_content" in reason

    def test_empty_content_rejected(self, sample_optimization_state):
        config = {
            "prompt_messages": [{"role": "user", "content": ""}],
            "model": "gpt-4",
        }
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "empty_role_or_content" in reason
