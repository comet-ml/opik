"""
End-to-end tests for prompt filtering functionality.
"""

import uuid
import opik


def test_get_prompts__no_filters(opik_client: opik.Opik):
    """Test get_prompts returns all prompts when no filters are applied."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Create test prompts
    prompt1 = opik_client.create_prompt(
        name=f"test-prompt-1-{unique_id}",
        prompt="Template 1: {{input}}",
        metadata={"environment": "test", "version": "1.0"}
    )
    prompt2 = opik_client.create_prompt(
        name=f"test-prompt-2-{unique_id}",
        prompt="Template 2: {{input}}",
        metadata={"environment": "staging", "version": "2.0"}
    )
    
    # Get all prompts
    all_prompts = opik_client.get_prompts()
    
    # Should find at least our test prompts
    prompt_names = [p.name for p in all_prompts]
    assert prompt1.name in prompt_names
    assert prompt2.name in prompt_names


def test_get_prompts__filter_by_metadata(opik_client: opik.Opik):
    """Test filtering prompts by metadata values."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Create test prompts with different metadata
    test_prompt = opik_client.create_prompt(
        name=f"production-prompt-{unique_id}",
        prompt="Production template: {{input}}",
        metadata={"environment": "production", "team": "nlp", "version": "1.5"}
    )
    staging_prompt = opik_client.create_prompt(
        name=f"staging-prompt-{unique_id}",
        prompt="Staging template: {{input}}",
        metadata={"environment": "staging", "team": "nlp", "version": "1.5"}
    )
    
    # Filter by environment
    production_prompts = opik_client.get_prompts(
        filters='metadata.environment = "production"'
    )
    
    # Should find the production prompt
    production_names = [p.name for p in production_prompts]
    assert test_prompt.name in production_names
    assert staging_prompt.name not in production_names
    
    # Verify metadata
    found_prompt = next((p for p in production_prompts if p.name == test_prompt.name), None)
    assert found_prompt is not None
    assert found_prompt.metadata["environment"] == "production"


def test_get_prompts__filter_by_name_pattern(opik_client: opik.Opik):
    """Test filtering prompts by name pattern."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Create test prompts
    chatbot_prompt = opik_client.create_prompt(
        name=f"chatbot-assistant-{unique_id}",
        prompt="You are a helpful chatbot: {{query}}",
        metadata={"type": "conversational"}
    )
    translation_prompt = opik_client.create_prompt(
        name=f"translation-service-{unique_id}",
        prompt="Translate to {{language}}: {{text}}",
        metadata={"type": "translation"}
    )
    
    # Filter by name pattern
    chatbot_prompts = opik_client.get_prompts(name="chatbot")
    
    # Should find the chatbot prompt
    chatbot_names = [p.name for p in chatbot_prompts]
    assert any("chatbot" in name for name in chatbot_names)
    
    # The specific test prompt should be found
    assert chatbot_prompt.name in chatbot_names
    assert translation_prompt.name not in chatbot_names


def test_get_prompts__complex_filter(opik_client: opik.Opik):
    """Test filtering with complex conditions."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Create test prompts with various metadata
    target_prompt = opik_client.create_prompt(
        name=f"nlp-production-{unique_id}",
        prompt="NLP production template: {{input}}",
        metadata={"team": "nlp", "environment": "production", "version": "2.0"}
    )
    other_prompt = opik_client.create_prompt(
        name=f"nlp-staging-{unique_id}",
        prompt="NLP staging template: {{input}}",
        metadata={"team": "nlp", "environment": "staging", "version": "2.0"}
    )
    
    # Complex filter: team AND environment
    filtered_prompts = opik_client.get_prompts(
        filters='metadata.team = "nlp" and metadata.environment = "production"'
    )
    
    # Should find only the production NLP prompt
    filtered_names = [p.name for p in filtered_prompts]
    assert target_prompt.name in filtered_names
    assert other_prompt.name not in filtered_names


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
        filters=f'metadata.test_group = "limit-test-{unique_id}"',
        max_results=3
    )
    
    # Should respect the limit
    assert len(limited_prompts) <= 3
    
    # Get all prompts for comparison
    all_test_prompts = opik_client.get_prompts(
        filters=f'metadata.test_group = "limit-test-{unique_id}"',
        max_results=100
    )
    
    # Should have created all 5 prompts
    assert len(all_test_prompts) == 5


def test_get_prompts__empty_result_with_filter(opik_client: opik.Opik):
    """Test that filtering returns empty list when no matches."""
    unique_id = str(uuid.uuid4())[-6:]
    
    # Filter for non-existent metadata
    filtered_prompts = opik_client.get_prompts(
        filters=f'metadata.nonexistent = "value-{unique_id}"'
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
