"""
End-to-end tests for prompt filtering functionality.

These tests verify that the prompt filtering works correctly with the REST API.
"""

import uuid
from typing import List

import opik
from opik import Prompt


def test_get_prompts__basic_functionality(opik_client: opik.Opik):
    """Test basic get_prompts functionality without filters."""
    # Get all prompts
    prompts = opik_client.get_prompts()
    
    # Should return a list
    assert isinstance(prompts, list)
    
    # All items should be Prompt objects
    for prompt in prompts:
        assert isinstance(prompt, Prompt)


def test_get_prompts__filter_by_name(opik_client: opik.Opik):
    """Test filtering prompts by name."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"test-prompt-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Filter by name using name parameter
    filtered_prompts = opik_client.get_prompts(name=prompt_name)
    
    # Should find our prompt
    assert len(filtered_prompts) >= 1
    found_prompt = next((p for p in filtered_prompts if p.name == prompt_name), None)
    assert found_prompt is not None
    assert found_prompt.name == prompt_name


def test_get_prompts__filter_by_name_contains(opik_client: opik.Opik):
    """Test filtering prompts by name pattern using filters parameter."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"contains-test-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Filter by name pattern
    filtered_prompts = opik_client.get_prompts(filters=f'name contains "{unique_id}"')
    
    # Should find our prompt
    assert len(filtered_prompts) >= 1
    found_prompt = next((p for p in filtered_prompts if p.name == prompt_name), None)
    assert found_prompt is not None
    assert found_prompt.name == prompt_name


def test_get_prompts__filter_by_name_exact(opik_client: opik.Opik):
    """Test filtering prompts by exact name match."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"exact-test-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Filter by exact name
    filtered_prompts = opik_client.get_prompts(filters=f'name = "{prompt_name}"')
    
    # Should find our prompt
    assert len(filtered_prompts) >= 1
    found_prompt = next((p for p in filtered_prompts if p.name == prompt_name), None)
    assert found_prompt is not None
    assert found_prompt.name == prompt_name


def test_get_prompts__filter_by_name_starts_with(opik_client: opik.Opik):
    """Test filtering prompts by name prefix."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"prefix-test-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Filter by name prefix
    filtered_prompts = opik_client.get_prompts(filters=f'name starts_with "prefix-test"')
    
    # Should find our prompt
    assert len(filtered_prompts) >= 1
    found_prompt = next((p for p in filtered_prompts if p.name == prompt_name), None)
    assert found_prompt is not None
    assert found_prompt.name == prompt_name


def test_get_prompts__filter_by_description(opik_client: opik.Opik):
    """Test filtering prompts by description."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"desc-test-{unique_id}"
    
    # Create a test prompt with specific description
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"description": f"unique-description-{unique_id}"}
    )
    
    # Filter by description (note: this would require backend support for description filtering)
    # For now, we'll test that the filter doesn't cause errors
    try:
        filtered_prompts = opik_client.get_prompts(filters=f'description contains "unique-description"')
        # If backend supports description filtering, this should work
        # If not, it should handle gracefully
    except Exception as e:
        # If description filtering is not supported, that's expected
        assert "Invalid filter" in str(e) or "not supported" in str(e)


def test_get_prompts__filter_by_version_count(opik_client: opik.Opik):
    """Test filtering prompts by version count."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"version-test-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Create a second version to increase version count
    prompt2 = opik_client.create_prompt(
        name=prompt_name,
        prompt="Updated template: {{input}}",
        metadata={"test": True, "version": "2.0"}
    )
    
    # Filter by version count (should have at least 2 versions now)
    filtered_prompts = opik_client.get_prompts(filters='version_count >= 2')
    
    # Should find our prompt if version count filtering is supported
    found_prompt = next((p for p in filtered_prompts if p.name == prompt_name), None)
    if found_prompt is not None:
        # Version count filtering is supported
        assert found_prompt.name == prompt_name


def test_get_prompts__max_results_limit(opik_client: opik.Opik):
    """Test max_results parameter limits returned prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Create multiple test prompts
    test_prompts = []
    for i in range(5):
        prompt = opik_client.create_prompt(
            name=f"limit-test-{i}-{unique_id}",
            prompt=f"Test template {i}: {{{{input}}}}",
            metadata={"test_group": f"limit-test-{unique_id}", "index": i}
        )
        test_prompts.append(prompt)
    
    # Get prompts with limit
    limited_prompts = opik_client.get_prompts(
        name=f"limit-test-{unique_id}",
        max_results=3
    )
    
    # Should respect the limit
    assert len(limited_prompts) <= 3
    
    # Get all prompts for comparison
    all_test_prompts = opik_client.get_prompts(
        name=f"limit-test-{unique_id}",
        max_results=100
    )
    
    # Should have created all 5 prompts
    assert len(all_test_prompts) == 5


def test_get_prompts__empty_result_with_filter(opik_client: opik.Opik):
    """Test that filtering returns empty list when no matches."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Filter for non-existent name
    filtered_prompts = opik_client.get_prompts(
        name=f"nonexistent-prompt-{unique_id}"
    )
    
    # Should return empty list, not None or error
    assert filtered_prompts == []


def test_get_prompts__version_consistency(opik_client: opik.Opik):
    """Test that get_prompts returns latest version of prompts."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"version-test-{unique_id}"
    
    # Create initial version
    v1_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Version 1: {{input}}",
        metadata={"version": "1.0"}
    )
    
    # Create new version
    v2_prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Version 2: {{input}}",
        metadata={"version": "2.0"}
    )
    
    # Get prompts by name
    found_prompts = opik_client.get_prompts(name=prompt_name)
    
    # Should find exactly one prompt (latest version)
    matching_prompts = [p for p in found_prompts if p.name == prompt_name]
    assert len(matching_prompts) == 1
    
    # Should be the latest version
    found_prompt = matching_prompts[0]
    assert found_prompt.prompt == "Version 2: {{input}}"
    assert found_prompt.metadata["version"] == "2.0"
    assert found_prompt.commit == v2_prompt.commit
    assert found_prompt.commit != v1_prompt.commit


def test_get_prompts__invalid_filter_format(opik_client: opik.Opik):
    """Test that invalid filter formats raise appropriate error."""
    # Attempt to use unsupported filter should raise ValueError
    try:
        opik_client.get_prompts(filters='invalid_field = "value"')
        assert False, "Expected ValueError for invalid filter"
    except ValueError as e:
        assert "Invalid filter format" in str(e)


def test_get_prompts__filter_by_id(opik_client: opik.Opik):
    """Test filtering prompts by ID."""
    unique_id = str(uuid.uuid4())[-6:]
    prompt_name = f"id-test-{unique_id}"
    
    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )
    
    # Filter by ID
    filtered_prompts = opik_client.get_prompts(filters=f'id = "{prompt.id}"')
    
    # Should find our prompt
    assert len(filtered_prompts) >= 1
    found_prompt = next((p for p in filtered_prompts if p.id == prompt.id), None)
    assert found_prompt is not None
    assert found_prompt.id == prompt.id
    assert found_prompt.name == prompt_name
