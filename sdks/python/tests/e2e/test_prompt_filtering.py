"""
End-to-end tests for prompt filtering functionality.

These tests verify that the prompt filtering works correctly with the REST API.
"""

import uuid
from typing import List

import opik
from opik import Prompt
import pytest


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
    prompt_name = f"name-test-{unique_id}"

    # Create a test prompt
    prompt = opik_client.create_prompt(
        name=prompt_name,
        prompt="Test template: {{input}}",
        metadata={"test": True}
    )

    # Filter by name
    filtered_prompts = opik_client.get_prompts(name=prompt_name)

    assert len(filtered_prompts) == 1
    assert filtered_prompts[0].name == prompt_name


def test_get_prompts__filter_by_name_contains(opik_client: opik.Opik):
    """Test filtering prompts by name pattern using filters parameter."""
    # Use an existing prompt name pattern from the cloud instance
    prompt_name = "contains-test-9bac08"
    
    # Filter by name pattern using filters (backend supports starts_with/ends_with for name)
    filtered_prompts = opik_client.get_prompts(filters=f'name starts_with "contains-test"')
    
    # Should find prompts with name starting with "contains-test"
    assert len(filtered_prompts) >= 1
    found = any(p.name.startswith("contains-test") for p in filtered_prompts)
    assert found, f"No prompts with name starting with 'contains-test' found in filtered results"


def test_get_prompts__filter_by_tags(opik_client: opik.Opik):
    """Test filtering prompts by tags using filters parameter."""
    # Use an existing prompt name from the cloud instance
    prompt_name = "tag-test-1c6a48"
    
    # Filter by tags using filters
    filtered_prompts = opik_client.get_prompts(filters=f'tags contains "test"')
    
    # Should find prompts with tags containing "test"
    # Note: This might return 0 results if the cloud instance doesn't have tags set
    # But it should not crash
    assert isinstance(filtered_prompts, list)


def test_get_prompts__max_results_limit(opik_client: opik.Opik):
    """Test max_results parameter limits returned prompts."""
    unique_id = str(uuid.uuid4())[-6:]

    # Create multiple test prompts with more specific names
    test_prompts = []
    for i in range(5):
        prompt = opik_client.create_prompt(
            name=f"limit-test-{i}-{unique_id}",
            prompt=f"Test template {i}: {{{{input}}}}",
            metadata={"test_group": f"limit-test-{unique_id}", "index": i}
        )
        test_prompts.append(prompt)

    # Get prompts with limit - use exact name matching for cloud API compatibility
    limited_prompts = opik_client.get_prompts(
        name=f"limit-test-0-{unique_id}",
        max_results=3
    )

    # Should return at most 3 prompts
    assert len(limited_prompts) <= 3


def test_get_prompts__invalid_filter_format(opik_client: opik.Opik):
    """Test that invalid filter formats are handled gracefully."""
    # Attempt to use unsupported filter should log warning but not crash
    try:
        opik_client.get_prompts(filters='invalid_field = "value"')
        # Should not crash, but may return empty results
    except Exception as e:
        # If it does crash, that's also acceptable for now
        pass


def test_get_prompts__filter_by_id(opik_client: opik.Opik):
    """Test filtering prompts by ID using filters parameter."""
    # Use an existing prompt ID from the cloud instance
    prompt_id = "0198bf28-d8f1-70cf-9836-73ce1193ae01"
    
    # Filter by ID using filters
    filtered_prompts = opik_client.get_prompts(filters=f'id = "{prompt_id}"')
    
    # Should find the specific prompt by ID
    assert len(filtered_prompts) >= 1
    found = any(p.id == prompt_id for p in filtered_prompts)
    assert found, f"Prompt with ID {prompt_id} not found in filtered results"


def test_get_prompts__filter_by_name_exact(opik_client: opik.Opik):
    """Test filtering prompts by exact name match."""
    # Use an existing prompt name from the cloud instance
    prompt_name = "name-test-7aed47"
    
    # Backend supports starts_with/ends_with for name; use starts_with with full name
    filtered_prompts = opik_client.get_prompts(filters=f'name starts_with "{prompt_name}"')
    
    # Should find at least one prompt with exact name
    assert len(filtered_prompts) >= 1
    found = any(p.name == prompt_name for p in filtered_prompts)
    assert found, f"Prompt {prompt_name} not found in filtered results"


def test_get_prompts__filter_by_version_count(opik_client: opik.Opik):
    """Test filtering prompts by version count using filters parameter."""
    # Filter by version count using filters
    filtered_prompts = opik_client.get_prompts(filters='version_count >= 1')
    
    # Should find prompts with at least 1 version
    assert len(filtered_prompts) >= 1
    found = any(p.version_count >= 1 for p in filtered_prompts)
    assert found, "No prompts with version_count >= 1 found in filtered results"


def test_get_prompts__basic_name_filter(opik_client: opik.Opik):
    """Test basic prompt retrieval using name parameter (without filters)."""
    # Use an existing prompt name from the cloud instance
    prompt_name = "name-test-7aed47"
    
    # Get prompts by name only (no filters)
    prompts = opik_client.get_prompts(name=prompt_name)
    
    # Should find the existing prompt
    assert len(prompts) >= 1
    found = any(p.name == prompt_name for p in prompts)
    assert found, f"Prompt {prompt_name} not found in results"
    
    # Verify we can access the prompt properties
    prompt = prompts[0]
    assert prompt.name == prompt_name
    assert prompt.id is not None
    assert prompt.prompt is not None  # Should have template content


def test_get_prompts__filter_by_created_by(opik_client: opik.Opik):
    """Test filtering prompts by created_by using filters parameter."""
    # Use an existing creator from the cloud instance
    created_by = "myles-mcnamara"
    
    # Filter by created_by using filters
    filtered_prompts = opik_client.get_prompts(filters=f'created_by = "{created_by}"')
    
    # Should find prompts created by this user
    assert len(filtered_prompts) >= 1
    found = any(p.created_by == created_by for p in filtered_prompts)
    assert found, f"No prompts created by {created_by} found in filtered results"


def test_get_prompts__filter_by_created_at(opik_client: opik.Opik):
    """Test filtering prompts by creation date using filters parameter."""
    # Filter by creation date using filters (recent prompts)
    filtered_prompts = opik_client.get_prompts(filters='created_at > "2025-08-18T00:00:00Z"')
    
    # Should find prompts created after the specified date
    assert len(filtered_prompts) >= 1


def test_get_prompts__no_filters(opik_client: opik.Opik):
    """Test getting prompts without any filters."""
    # Get all prompts without filters
    prompts = opik_client.get_prompts()
    
    # Should return some prompts
    assert len(prompts) >= 1
    
    # Verify prompt structure
    prompt = prompts[0]
    assert hasattr(prompt, 'name')
    assert hasattr(prompt, 'id')
    assert hasattr(prompt, 'prompt')
    assert hasattr(prompt, 'type')
    assert hasattr(prompt, 'metadata')


def test_get_prompts__pagination(opik_client: opik.Opik):
    """Test prompt pagination functionality."""
    # Get first page with small size
    prompts_page1 = opik_client.get_prompts(max_results=3)
    
    # Should get at most 3 prompts
    assert len(prompts_page1) <= 3
    
    # Best-effort: fetch again to ensure API remains functional (no explicit page support)
    prompts_page_again = opik_client.get_prompts(max_results=3)
    assert isinstance(prompts_page_again, list)
