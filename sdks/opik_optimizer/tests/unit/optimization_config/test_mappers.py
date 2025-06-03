"""Tests for the mappers module."""

import pytest

from opik_optimizer.optimization_config.mappers import (
    from_dataset_field,
    from_llm_response_text,
    from_agent_output,
)


class TestMappers:
    def test_from_dataset_field_with_name(self):
        mapper = from_dataset_field(name="test_field")
        assert isinstance(mapper, str)

    def test_from_dataset_field_with_transform(self):
        mapper = from_dataset_field(transform=lambda x: x["test_field"])
        assert callable(mapper)

    def test_from_dataset_field_with_both(self):
        with pytest.raises(ValueError):
            from_dataset_field(
                name="test_field",
                transform=lambda x: x["test_field"]
            )

    def test_from_dataset_field_with_none(self):
        with pytest.raises(ValueError):
            from_dataset_field()

    def test_from_llm_response_text(self):
        mapper = from_llm_response_text()
        assert isinstance(mapper, str)

    def test_from_agent_output_with_name(self):
        mapper = from_agent_output(name="test_field")
        assert callable(mapper)

    def test_from_agent_output_with_transform(self):
        mapper = from_agent_output(transform=lambda x: x["test_field"])
        assert callable(mapper)

    def test_from_agent_output_with_both(self):
        with pytest.raises(ValueError):
            from_agent_output(
                name="test_field",
                transform=lambda x: x["test_field"]
            )

    def test_from_agent_output_with_none(self):
        mapper = from_agent_output()
        assert isinstance(mapper, str) 
