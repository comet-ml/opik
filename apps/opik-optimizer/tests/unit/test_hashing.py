from opik_optimizer_framework.util.hashing import canonical_config_hash


class TestCanonicalConfigHash:
    def test_determinism(self):
        config = {"model": "gpt-4", "messages": [{"role": "user", "content": "hi"}]}
        assert canonical_config_hash(config) == canonical_config_hash(config)

    def test_key_ordering_invariance(self):
        config_a = {"model": "gpt-4", "temperature": 0.7}
        config_b = {"temperature": 0.7, "model": "gpt-4"}
        assert canonical_config_hash(config_a) == canonical_config_hash(config_b)

    def test_different_inputs_different_hashes(self):
        config_a = {"model": "gpt-4", "temperature": 0.7}
        config_b = {"model": "gpt-4", "temperature": 0.8}
        assert canonical_config_hash(config_a) != canonical_config_hash(config_b)

    def test_hash_length(self):
        config = {"key": "value"}
        result = canonical_config_hash(config)
        assert len(result) == 16
        assert all(c in "0123456789abcdef" for c in result)

    def test_nested_key_ordering(self):
        config_a = {"outer": {"b": 2, "a": 1}}
        config_b = {"outer": {"a": 1, "b": 2}}
        assert canonical_config_hash(config_a) == canonical_config_hash(config_b)

    def test_empty_config(self):
        result = canonical_config_hash({})
        assert len(result) == 16

    def test_complex_config(self):
        config = {
            "model": "openai/gpt-4o",
            "messages": [
                {"role": "system", "content": "You are helpful."},
                {"role": "user", "content": "Summarize: {text}"},
            ],
            "parameters": {"temperature": 0.7, "max_tokens": 1000},
        }
        result = canonical_config_hash(config)
        assert len(result) == 16
        assert canonical_config_hash(config) == result
