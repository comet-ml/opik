# Conversation Markdown Converter

This utility converts JSON conversation data (like OpenAI API format) into readable markdown format.

## Usage

```typescript
import { convertConversationToMarkdown } from "@/lib/traces";

const conversationData = {
  model: "openai/gpt-4o-mini",
  messages: [
    {
      role: "system",
      content: "You are a helpful AI assistant."
    },
    {
      role: "user", 
      content: "What is AI?"
    },
    {
      role: "assistant",
      content: "AI stands for Artificial Intelligence..."
    }
  ]
};

// Basic conversion
const markdown = convertConversationToMarkdown(conversationData);

// With additional options
const detailedMarkdown = convertConversationToMarkdown(conversationData, {
  includeModel: true,    // Include model information (default: true)
  includeTools: true,    // Include available tools section (default: false)
  includeKwargs: true    // Include configuration/kwargs section (default: false)
});
```

## Features

- **Role-based formatting**: Each message role (system, user, assistant, tool) gets its own section
- **Tool call support**: Displays function calls with arguments
- **Tool responses**: Shows tool responses with call IDs
- **Configurable output**: Choose what metadata to include
- **Clean markdown**: Properly formatted for readability

## Output Format

The function generates markdown in this format:

```markdown
# Model
openai/gpt-4o-mini

# System
You are a helpful AI assistant.

# User
What is AI?

# Assistant
## Tool call: search_wikipedia
**Function:** search_wikipedia
**Arguments:** {"query":"artificial intelligence"}

# Tool
## Tool response for: call_123
["AI is the simulation of human intelligence..."]

# Assistant
AI stands for Artificial Intelligence...
```

## TypeScript Support

Full TypeScript support with proper interfaces:

- `ConversationData` - Main conversation object
- `ConversationMessage` - Individual message
- `ToolCall` - Tool call structure
- `Tool` - Tool definition

## Examples

See `conversationMarkdownExample.ts` for complete usage examples.
