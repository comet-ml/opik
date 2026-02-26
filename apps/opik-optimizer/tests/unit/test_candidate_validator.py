from dataclasses import asdict

from opik_optimizer_framework.candidate_validator import validate_candidate
from opik_optimizer_framework.types import CandidateConfig, OptimizationState
from opik_optimizer_framework.util.hashing import canonical_config_hash


class TestCandidateValidator:
    def test_valid_candidate(self, sample_candidate_config, sample_optimization_state):
        valid, reason = validate_candidate(sample_candidate_config, sample_optimization_state)
        assert valid is True
        assert reason is None

    def test_empty_messages_rejected(self, sample_optimization_state):
        config = CandidateConfig(prompt_messages=[], model="gpt-4")
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert reason == "empty_messages"

    def test_missing_role_rejected(self, sample_optimization_state):
        config = CandidateConfig(
            prompt_messages=[{"content": "hi"}],
            model="gpt-4",
        )
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "missing_role_or_content" in reason

    def test_missing_content_rejected(self, sample_optimization_state):
        config = CandidateConfig(
            prompt_messages=[{"role": "user"}],
            model="gpt-4",
        )
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "missing_role_or_content" in reason

    def test_empty_role_rejected(self, sample_optimization_state):
        config = CandidateConfig(
            prompt_messages=[{"role": "", "content": "hi"}],
            model="gpt-4",
        )
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "empty_role_or_content" in reason

    def test_empty_content_rejected(self, sample_optimization_state):
        config = CandidateConfig(
            prompt_messages=[{"role": "user", "content": ""}],
            model="gpt-4",
        )
        valid, reason = validate_candidate(config, sample_optimization_state)
        assert valid is False
        assert "empty_role_or_content" in reason

    def test_duplicate_hash_rejected(self, sample_candidate_config, sample_optimization_state):
        config_hash = canonical_config_hash(asdict(sample_candidate_config))
        sample_optimization_state.seen_hashes.add(config_hash)

        valid, reason = validate_candidate(sample_candidate_config, sample_optimization_state)
        assert valid is False
        assert reason == "duplicate_config_hash"

    def test_different_config_not_duplicate(self, sample_optimization_state):
        config_a = CandidateConfig(
            prompt_messages=[{"role": "user", "content": "Version A"}],
            model="gpt-4",
        )
        config_b = CandidateConfig(
            prompt_messages=[{"role": "user", "content": "Version B"}],
            model="gpt-4",
        )
        valid_a, _ = validate_candidate(config_a, sample_optimization_state)
        assert valid_a is True

        sample_optimization_state.seen_hashes.add(
            canonical_config_hash(asdict(config_a))
        )

        valid_b, _ = validate_candidate(config_b, sample_optimization_state)
        assert valid_b is True
