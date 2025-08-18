# Prompt Filtering in Opik Python SDK

This document describes the new prompt filtering functionality available in the Opik Python SDK, which allows you to search and retrieve prompts based on metadata, tags, and other attributes.

## Overview

The new `get_prompts()` method provides powerful filtering capabilities similar to what's available for traces and spans. This addresses GitHub issue #3033 by providing Python SDK support for the REST API's filtering functionality.

## API Reference

### `Opik.get_prompts()`

```python
def get_prompts(
    self,
    filters: Optional[str] = None,
    name: Optional[str] = None,
    max_results: int = 1000,
) -> List[Prompt]:
```

**Parameters:**
- `filters` (Optional[str]): Filter string using Opik query language
- `name` (Optional[str]): Prompt name filter (exact match or substring)
- `max_results` (int): Maximum number of prompts to return (default: 1000)

**Returns:**
- `List[Prompt]`: List of Prompt objects matching the specified filters

## Filter Syntax

The `filters` parameter supports Opik's query language with the following operators:

### Basic Operators
- `=` - Exact match
- `!=` - Not equal
- `contains` - Substring/array contains
- `>`, `<`, `>=`, `<=` - Numeric/date comparisons

### Logical Operators
- `and` - Logical AND
- `or` - Logical OR
- Parentheses for grouping: `(condition1) and (condition2)`

### Field References
- `metadata.key` - Access metadata fields
- `tags` - Access tags array
- `name` - Prompt name
- `created_at` - Creation timestamp
- `created_by` - Creator

## Usage Examples

### Basic Usage

```python
import opik

# Initialize client
client = opik.Opik()

# Get all prompts
all_prompts = client.get_prompts()
print(f"Found {len(all_prompts)} prompts")
```

### Filtering by Metadata

```python
# Filter by environment
production_prompts = client.get_prompts(
    filters='metadata.environment = "production"'
)

# Filter by team
nlp_prompts = client.get_prompts(
    filters='metadata.team = "nlp"'
)

# Filter by version
v1_prompts = client.get_prompts(
    filters='metadata.version = "1.0"'
)
```

### Filtering by Tags

```python
# Find prompts with specific tag
active_prompts = client.get_prompts(
    filters='tags contains "active"'
)

# Find prompts with production tag
prod_tagged = client.get_prompts(
    filters='tags contains "production"'
)
```

### Name-Based Filtering

```python
# Filter by name pattern
chatbot_prompts = client.get_prompts(name="chatbot")

# This will find prompts like:
# - "chatbot-assistant"
# - "customer-chatbot"
# - "chatbot-v2"
```

### Complex Filtering

```python
# Multiple conditions with AND
specific_prompts = client.get_prompts(
    filters='metadata.team = "nlp" and metadata.environment = "production"'
)

# Multiple conditions with OR
dev_prompts = client.get_prompts(
    filters='metadata.environment = "development" or metadata.environment = "staging"'
)

# Complex nested conditions
complex_prompts = client.get_prompts(
    filters='(metadata.team = "nlp" or metadata.team = "ml") and tags contains "v2"'
)
```

### Date-Based Filtering

```python
# Find recent prompts
recent_prompts = client.get_prompts(
    filters='created_at > "2024-01-01"'
)

# Find prompts from specific time range
prompts_in_range = client.get_prompts(
    filters='created_at >= "2024-01-01" and created_at < "2024-02-01"'
)
```

### Limiting Results

```python
# Get only first 10 prompts
limited_prompts = client.get_prompts(max_results=10)

# Combine with filters
top_production = client.get_prompts(
    filters='metadata.environment = "production"',
    max_results=5
)
```

## Complete Example

```python
import opik

def main():
    # Initialize client
    client = opik.Opik()
    
    # Create some test prompts
    client.create_prompt(
        name="chatbot_assistant",
        prompt="You are a helpful assistant: {{query}}",
        metadata={
            "environment": "production",
            "team": "customer-service",
            "version": "2.1"
        }
    )
    
    client.create_prompt(
        name="code_reviewer",
        prompt="Review this code: {{code}}",
        metadata={
            "environment": "staging", 
            "team": "engineering",
            "version": "1.0"
        }
    )
    
    # Find all production prompts
    prod_prompts = client.get_prompts(
        filters='metadata.environment = "production"'
    )
    
    print(f"Production prompts: {len(prod_prompts)}")
    for prompt in prod_prompts:
        print(f"- {prompt.name}: {prompt.metadata}")
    
    # Find customer service team prompts
    cs_prompts = client.get_prompts(
        filters='metadata.team = "customer-service"'
    )
    
    print(f"Customer service prompts: {len(cs_prompts)}")
    
    # Find prompts by name pattern
    chatbot_prompts = client.get_prompts(name="chatbot")
    print(f"Chatbot prompts: {len(chatbot_prompts)}")

if __name__ == "__main__":
    main()
```

## Migration from REST API

If you were previously using the REST API directly for prompt filtering, you can now use the SDK:

### Before (REST API)
```python
import requests

response = requests.get(
    "https://api.opik.com/v1/prompts",
    params={"filters": 'metadata.environment = "production"'},
    headers={"Authorization": "Bearer YOUR_TOKEN"}
)
prompts = response.json()
```

### After (Python SDK)
```python
import opik

client = opik.Opik()
prompts = client.get_prompts(filters='metadata.environment = "production"')
```

## Performance Considerations

- The method automatically handles pagination for large result sets
- Use `max_results` to limit memory usage for very large prompt collections
- Specific filters are more efficient than broad searches
- Consider using name filtering combined with metadata filtering for best performance

## Error Handling

```python
try:
    prompts = client.get_prompts(filters='invalid.syntax')
except Exception as e:
    print(f"Filter error: {e}")
    # Handle invalid filter syntax
```

## Comparison with Existing Methods

| Method | Use Case | Returns |
|--------|----------|---------|
| `get_prompt(name, commit)` | Get specific prompt version | Single `Prompt` or `None` |
| `get_all_prompts(name)` | Get all versions of named prompt | `List[Prompt]` |
| `get_prompts(filters, name, max_results)` | **NEW:** Search with filters | `List[Prompt]` |

## Implementation Details

### Architecture
- Built on existing REST API `/v1/private/prompts` endpoint
- Uses efficient pagination to handle large result sets
- Returns latest version of each matching prompt
- Consistent with trace and span filtering patterns

### Limitations
- Returns only latest version of each prompt (not all versions)
- Filter syntax must be valid Opik query language
- Some complex nested queries may have performance implications

## Related Issues

- Resolves GitHub issue #3033: Python SDK support for get_prompt with filters
- Addresses missing documentation for REST API filters

## Future Enhancements

Potential future improvements could include:
- Support for retrieving specific versions in filtered results
- Additional filter operators (regex, fuzzy matching)
- Sorting options for filtered results
- Aggregate functions (count, grouping)
