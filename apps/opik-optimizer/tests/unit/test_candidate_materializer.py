import uuid

from opik_optimizer_framework.candidate_materializer import materialize_candidate


class TestCandidateMaterializer:
    def test_generates_uuid_candidate_id(self, sample_candidate_config):
        candidate = materialize_candidate(sample_candidate_config, step_index=0)
        uuid.UUID(candidate.candidate_id)

    def test_preserves_config(self, sample_candidate_config):
        candidate = materialize_candidate(sample_candidate_config, step_index=0)
        assert candidate.config == sample_candidate_config

    def test_preserves_step_index(self, sample_candidate_config):
        candidate = materialize_candidate(sample_candidate_config, step_index=3)
        assert candidate.step_index == 3

    def test_default_empty_parent_ids(self, sample_candidate_config):
        candidate = materialize_candidate(sample_candidate_config, step_index=0)
        assert candidate.parent_candidate_ids == []

    def test_with_parent_ids(self, sample_candidate_config):
        parents = ["parent-1", "parent-2"]
        candidate = materialize_candidate(
            sample_candidate_config,
            step_index=1,
            parent_candidate_ids=parents,
        )
        assert candidate.parent_candidate_ids == parents

    def test_unique_candidate_ids(self, sample_candidate_config):
        c1 = materialize_candidate(sample_candidate_config, step_index=0)
        c2 = materialize_candidate(sample_candidate_config, step_index=0)
        assert c1.candidate_id != c2.candidate_id
