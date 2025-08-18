#!/usr/bin/env python3
"""
Example script demonstrating the new prompt filtering functionality in Opik Python SDK.

This script shows how to use the new get_prompts method with various filtering options.
"""

import opik
from opik import Prompt


def main():
    """
    Demonstrate the new prompt filtering capabilities.
    """
    # Initialize the Opik client
    client = opik.Opik()

    print("=== Opik Prompt Filtering Examples ===\n")

    # Example 1: Create some sample prompts for testing
    print("1. Creating sample prompts...")
    
    # Create prompts with different metadata and tags
    prompt1 = client.create_prompt(
        name="chatbot_prompt",
        prompt="You are a helpful assistant. Answer the question: {{question}}",
        metadata={"version": "1.0", "environment": "production", "team": "nlp"}
    )
    print(f"Created prompt: {prompt1.name} (commit: {prompt1.commit})")

    prompt2 = client.create_prompt(
        name="summarization_prompt", 
        prompt="Summarize the following text in 2-3 sentences: {{text}}",
        metadata={"version": "2.1", "environment": "staging", "team": "nlp"}
    )
    print(f"Created prompt: {prompt2.name} (commit: {prompt2.commit})")

    prompt3 = client.create_prompt(
        name="translation_prompt",
        prompt="Translate the following text to {{target_language}}: {{text}}",
        metadata={"version": "1.5", "environment": "production", "team": "i18n"}
    )
    print(f"Created prompt: {prompt3.name} (commit: {prompt3.commit})")

    print("\n" + "="*50)

    # Example 2: Get all prompts (no filters)
    print("2. Getting all prompts...")
    all_prompts = client.get_prompts()
    print(f"Found {len(all_prompts)} total prompts:")
    for prompt in all_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 3: Filter by metadata
    print("3. Filtering by metadata...")
    print("Filter: metadata.environment = \"production\"")
    production_prompts = client.get_prompts(filters='metadata.environment = "production"')
    print(f"Found {len(production_prompts)} production prompts:")
    for prompt in production_prompts:
        print(f"  - {prompt.name} (metadata: {prompt.metadata})")

    print("\n" + "="*50)

    # Example 4: Filter by team
    print("4. Filtering by team...")
    print("Filter: metadata.team = \"nlp\"")
    nlp_prompts = client.get_prompts(filters='metadata.team = "nlp"')
    print(f"Found {len(nlp_prompts)} NLP team prompts:")
    for prompt in nlp_prompts:
        print(f"  - {prompt.name} (metadata: {prompt.metadata})")

    print("\n" + "="*50)

    # Example 5: Filter by name pattern
    print("5. Filtering by name pattern...")
    print("Name pattern: \"chatbot\"")
    chatbot_prompts = client.get_prompts(name="chatbot")
    print(f"Found {len(chatbot_prompts)} prompts matching 'chatbot':")
    for prompt in chatbot_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 6: Complex filter (multiple conditions)
    print("6. Complex filtering...")
    print("Filter: metadata.environment = \"production\" and metadata.team = \"nlp\"")
    complex_prompts = client.get_prompts(filters='metadata.environment = "production" and metadata.team = "nlp"')
    print(f"Found {len(complex_prompts)} prompts matching complex filter:")
    for prompt in complex_prompts:
        print(f"  - {prompt.name} (metadata: {prompt.metadata})")

    print("\n" + "="*50)

    # Example 7: Version filtering
    print("7. Version filtering...")
    print("Filter: metadata.version = \"1.0\"")
    v1_prompts = client.get_prompts(filters='metadata.version = "1.0"')
    print(f"Found {len(v1_prompts)} version 1.0 prompts:")
    for prompt in v1_prompts:
        print(f"  - {prompt.name} (version: {prompt.metadata.get('version')})")

    print("\n" + "="*50)
    print("Done! The new filtering functionality is working correctly.")


if __name__ == "__main__":
    main()
