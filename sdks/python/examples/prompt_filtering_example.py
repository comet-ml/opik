#!/usr/bin/env python3
"""
Example script demonstrating the new prompt filtering functionality in Opik Python SDK.

This script shows how to use the new get_prompts method with various filtering options.
The SDK uses the same query language as other Opik filtering methods.
"""

import opik


def main():
    """Demonstrate prompt filtering functionality."""
    print("=== Opik Python SDK - Prompt Filtering Examples ===")
    print()

    # Initialize the client
    client = opik.Opik()

    # Example 1: Basic usage - get all prompts
    print("1. Getting all prompts...")
    all_prompts = client.get_prompts()
    print(f"Found {len(all_prompts)} prompts")
    for prompt in all_prompts[:3]:  # Show first 3
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 2: Filtering by name using the name parameter
    print("2. Filtering by name using 'name' parameter...")
    print("Getting prompts with 'chatbot' in the name:")
    chatbot_prompts = client.get_prompts(name="chatbot")
    print(f"Found {len(chatbot_prompts)} chatbot prompts")
    for prompt in chatbot_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 3: Filtering by name using filters parameter
    print("3. Filtering by name using 'filters' parameter...")
    print("Getting prompts with 'assistant' in the name:")
    assistant_prompts = client.get_prompts(filters='name contains "assistant"')
    print(f"Found {len(assistant_prompts)} assistant prompts")
    for prompt in assistant_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 4: Exact name matching
    print("4. Exact name matching...")
    print("Getting prompt with exact name 'chatbot_prompt':")
    exact_prompts = client.get_prompts(filters='name = "chatbot_prompt"')
    print(f"Found {len(exact_prompts)} exact matches")
    for prompt in exact_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 5: Name prefix filtering
    print("5. Name prefix filtering...")
    print("Getting prompts whose names start with 'chat':")
    chat_prompts = client.get_prompts(filters='name starts_with "chat"')
    print(f"Found {len(chat_prompts)} prompts starting with 'chat'")
    for prompt in chat_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 6: Description filtering
    print("6. Description filtering...")
    print("Getting prompts with 'helpful' in the description:")
    helpful_prompts = client.get_prompts(filters='description contains "helpful"')
    print(f"Found {len(helpful_prompts)} helpful prompts")
    for prompt in helpful_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 7: Version count filtering
    print("7. Version count filtering...")
    print("Getting prompts with more than 2 versions:")
    complex_prompts = client.get_prompts(filters='version_count > 2')
    print(f"Found {len(complex_prompts)} prompts with more than 2 versions")
    for prompt in complex_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 8: Tags filtering examples
    print("8. Tags filtering examples (for reference):")
    print("Note: These examples show the syntax for tags filtering.")
    print("The SDK uses the same query language as other Opik filtering methods.")
    print("")
    print("Filter by tags containing 'production':")
    print("  client.get_prompts(filters='tags contains \"production\"')")
    print("")
    print("Filter out prompts with 'deprecated' tag:")
    print("  client.get_prompts(filters='tags not_contains \"deprecated\"')")
    print("")
    print("Get prompts with no tags:")
    print("  client.get_prompts(filters='tags is_empty')")
    print("")
    print("Get prompts that have tags:")
    print("  client.get_prompts(filters='tags is_not_empty')")

    print("\n" + "="*50)

    # Example 9: Date filtering examples
    print("9. Date filtering examples (for reference):")
    print("Filter prompts created after 2024-01-01:")
    print("  client.get_prompts(filters='created_at > \"2024-01-01\"')")
    print("")
    print("Filter prompts updated before 2024-12-31:")
    print("  client.get_prompts(filters='last_updated_at <= \"2024-12-31\"')")

    print("\n" + "="*50)

    # Example 10: User filtering examples
    print("10. User filtering examples (for reference):")
    print("Filter prompts created by specific user:")
    print("  client.get_prompts(filters='created_by = \"user@example.com\"')")
    print("")
    print("Filter prompts updated by users with 'admin' in name:")
    print("  client.get_prompts(filters='last_updated_by contains \"admin\"')")

    print("\n" + "="*50)

    # Example 11: Limiting results
    print("11. Limiting results...")
    print("Getting first 2 prompts:")
    limited_prompts = client.get_prompts(max_results=2)
    print(f"Found {len(limited_prompts)} prompts (limited to 2):")
    for prompt in limited_prompts:
        print(f"  - {prompt.name}")

    print("\n" + "="*50)

    # Example 12: Error handling for invalid filters
    print("12. Error handling for invalid filters...")
    print("Attempting to use invalid filter:")
    try:
        invalid_prompts = client.get_prompts(filters='invalid_field = "value"')
        print("This should not be reached")
    except ValueError as e:
        print(f"Expected error: {e}")

    print("\n" + "="*50)
    print("=== End of Examples ===")
    print("\nKey takeaways:")
    print("- Comprehensive filtering is supported for prompts")
    print("- Uses the same query language as other Opik filtering methods")
    print("- Supports string, date, and numeric field filtering")
    print("- Use clear error messages to guide users to supported filters")
    print("- Name filtering can be done via 'name' parameter or 'filters' parameter")


if __name__ == "__main__":
    main()
