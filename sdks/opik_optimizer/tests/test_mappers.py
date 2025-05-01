"""Tests for the mappers module."""

import pytest
from unittest.mock import Mock

from opik_optimizer.optimization_config.mappers import (
    Mapper,
    from_dataset_field,
    from_llm_response_text,
    from_agent_output,
    EVALUATED_LLM_TASK_OUTPUT,
)

class TestMappers:
    def test_from_dataset_field_with_name(self):
        mapper = from_dataset_field(name="test_field")
        assert isinstance(mapper, str)

    def test_from_dataset_field_with_transform(self):
        transform = lambda x: x["test_field"]
        mapper = from_dataset_field(transform=transform)
        assert callable(mapper)

    def test_from_dataset_field_with_both(self):
        with pytest.raises(ValueError):
            from_dataset_field(name="test_field", transform=lambda x: x["test_field"])

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
        transform = lambda x: x["test_field"]
        mapper = from_agent_output(transform=transform)
        assert callable(mapper)

    def test_from_agent_output_with_both(self):
        with pytest.raises(ValueError):
            from_agent_output(name="test_field", transform=lambda x: x["test_field"])

    def test_from_agent_output_with_none(self):
        mapper = from_agent_output()
        assert isinstance(mapper, str) 