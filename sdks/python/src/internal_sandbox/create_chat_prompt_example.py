"""
Example script to create a chat prompt using the Opik SDK.

This demonstrates how to use the ChatPrompt class to create a chat-style prompt
that will display with the nice UI in the frontend.
"""

import opik

# Initialize Opik client
client = opik.Opik()

# Example 1: Create a simple chat prompt for a helpful assistant
print("Creating 'helpful-assistant' chat prompt...")
helpful_assistant = client.create_chat_prompt(
    name="helpful-assistant",
    messages=[
        {"role": "system", "content": "You are a helpful AI assistant that provides clear and concise answers."},
        {"role": "user", "content": "{{user_question}}"},
    ],
)
print(f"✓ Created chat prompt: {helpful_assistant.name} (commit: {helpful_assistant.commit})")

# Example 2: Create a criticism assistant (like the one in the screenshot)
print("\nCreating 'criticism-assistant-v2' chat prompt...")
criticism_assistant = client.create_chat_prompt(
    name="criticism-assistant-v2",
    messages=[
        {"role": "system", "content": "You're a helpful assistant that always has its word"},
        {"role": "user", "content": "Always be critical to what I say"},
        {"role": "assistant", "content": "I will"},
    ],
)
print(f"✓ Created chat prompt: {criticism_assistant.name} (commit: {criticism_assistant.commit})")

# Example 3: Create a more complex chat prompt with multiple exchanges
print("\nCreating 'code-reviewer' chat prompt...")
code_reviewer = client.create_chat_prompt(
    name="code-reviewer",
    messages=[
        {
            "role": "system",
            "content": "You are an expert code reviewer. Provide constructive feedback on code quality, best practices, and potential improvements."
        },
        {
            "role": "user",
            "content": "Please review this code:\n\n{{code_snippet}}"
        },
        {
            "role": "assistant",
            "content": "I'll review your code and provide detailed feedback on:\n1. Code quality and readability\n2. Best practices\n3. Potential bugs or issues\n4. Suggestions for improvement"
        },
    ],
)
print(f"✓ Created chat prompt: {code_reviewer.name} (commit: {code_reviewer.commit})")

# Example 4: Create a chat prompt with metadata
print("\nCreating 'customer-support' chat prompt with metadata...")
customer_support = client.create_chat_prompt(
    name="customer-support",
    messages=[
        {
            "role": "system",
            "content": "You are a friendly customer support agent. Be empathetic, professional, and solution-oriented."
        },
        {
            "role": "user",
            "content": "{{customer_issue}}"
        },
    ],
    metadata={
        "department": "support",
        "tone": "friendly",
        "response_time_target": "5min"
    }
)
print(f"✓ Created chat prompt: {customer_support.name} (commit: {customer_support.commit})")

# Example 5: Demonstrate format() method
print("\n" + "="*60)
print("Testing format() method with variables...")
print("="*60)

formatted = helpful_assistant.format(variables={"user_question": "What is the capital of France?"})
print("\nFormatted prompt:")
print(formatted)

print("\n✅ All chat prompts created successfully!")
print("\nYou can now view these prompts in the Opik UI with the beautiful chat interface.")
print("Navigate to the Prompts page to see them displayed with role badges and colors!")

