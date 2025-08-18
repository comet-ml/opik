# Prompt Filtering in Opik Python SDK

This document describes the new prompt filtering functionality available in the Opik Python SDK, which allows you to search and retrieve prompts based on various attributes.

## Overview

The new `get_prompts()` method provides filtering capabilities for prompts. This addresses GitHub issue #3033 by providing Python SDK support for the REST API's filtering functionality.

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

The `filters` parameter uses the same query language as other Opik filtering methods (like `search_traces`). The SDK automatically converts filter strings to the backend's expected format.

### Supported Fields
- **id**: Filter by prompt ID
- **name**: Filter by prompt name
- **description**: Filter by prompt description
- **created_at**: Filter by creation date
- **last_updated_at**: Filter by last update date
- **created_by**: Filter by creator
- **last_updated_by**: Filter by last updater
- **tags**: Filter by prompt tags
- **version_count**: Filter by number of versions

### Supported Operators
- **=**: Exact match
- **!=**: Not equal
- **contains**: Check if field contains the specified value
- **not_contains**: Check if field does not contain the specified value
- **starts_with**: Check if field starts with the specified value
- **ends_with**: Check if field ends with the specified value
- **is_empty**: Check if field is empty
- **is_not_empty**: Check if field is not empty
- **>**: Greater than (for numeric fields)
- **>=**: Greater than or equal (for numeric fields)
- **<**: Less than (for numeric fields)
- **<=**: Less than or equal (for numeric fields)

### Filter Format
Filters are specified as a string in the format: `field operator "value"`

The SDK uses the existing `OpikQueryLanguage` parser to convert these to the backend's expected JSON format.

### Examples by Field Type

#### Tags Filtering
- `tags contains "production"` - Filter prompts that contain the specified tag
- `tags not_contains "staging"` - Filter prompts that do not contain the specified tag
- `tags is_empty` - Filter prompts with no tags
- `tags is_not_empty` - Filter prompts that have tags

#### Name Filtering
- `name = "exact_name"` - Filter by exact name match
- `name contains "pattern"` - Filter by name pattern
- `name starts_with "chat"` - Filter prompts whose names start with "chat"
- `name ends_with "prompt"` - Filter prompts whose names end with "prompt"

#### ID Filtering
- `id = "prompt-id"` - Filter by exact prompt ID
- `id != "prompt-id"` - Filter out specific prompt ID

#### Description Filtering
- `description contains "chatbot"` - Filter prompts with "chatbot" in description
- `description is_empty` - Filter prompts with no description

#### Date Filtering
- `created_at > "2024-01-01"` - Filter prompts created after date
- `last_updated_at <= "2024-12-31"` - Filter prompts updated before date

#### User Filtering
- `created_by = "user@example.com"` - Filter prompts created by specific user
- `last_updated_by contains "admin"` - Filter prompts updated by users with "admin" in name

#### Version Count Filtering
- `version_count > 5` - Filter prompts with more than 5 versions
- `version_count <= 10` - Filter prompts with 10 or fewer versions

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

### Filtering by Tags

```python
# Filter by tags containing "production"
production_prompts = client.get_prompts(
    filters='tags contains "production"'
)

# Filter out prompts with "deprecated" tag
active_prompts = client.get_prompts(
    filters='tags not_contains "deprecated"'
)

# Get prompts with no tags
untagged_prompts = client.get_prompts(
    filters='tags is_empty'
)

# Get prompts that have tags
tagged_prompts = client.get_prompts(
    filters='tags is_not_empty'
)
```

### Filtering by Name

```python
# Filter by exact name
specific_prompt = client.get_prompts(
    name="my-chatbot-prompt"
)

# Filter by name pattern
chatbot_prompts = client.get_prompts(
    name="chatbot"
)

# Using filters parameter for exact name match
exact_prompts = client.get_prompts(
    filters='name = "my-prompt"'
)

# Using filters parameter for name pattern
pattern_prompts = client.get_prompts(
    filters='name contains "assistant"'
)

# Filter by name prefix
chat_prompts = client.get_prompts(
    filters='name starts_with "chat"'
)
```

### Filtering by Other Fields

```python
# Filter by description
chatbot_prompts = client.get_prompts(
    filters='description contains "chatbot"'
)

# Filter by creation date
recent_prompts = client.get_prompts(
    filters='created_at > "2024-01-01"'
)

# Filter by creator
user_prompts = client.get_prompts(
    filters='created_by = "user@example.com"'
)

# Filter by version count
complex_prompts = client.get_prompts(
    filters='version_count > 5'
)
```

### Limiting Results

```python
# Limit to first 10 results
limited_prompts = client.get_prompts(
    filters='tags contains "active"',
    max_results=10
)
```

## Error Handling

### Invalid Filter Format

Invalid filter formats will provide helpful error messages:

```python
try:
    prompts = client.get_prompts(filters='invalid_field = "value"')
except ValueError as e:
    print(e)
    # Output: Invalid filter format: invalid_field = "value"
```

## Backend Limitations

The backend REST API for prompts supports filtering by all the fields listed above. The filtering capabilities are comprehensive and include:

- **String fields**: id, name, description, created_by, last_updated_by, tags
- **Date fields**: created_at, last_updated_at  
- **Numeric fields**: version_count

## Migration from Other Resources

If you're familiar with filtering for traces or spans, note that prompt filtering uses the same query language:

```python
# ✅ Works for traces/spans
traces = client.search_traces(filters='metadata.environment = "production"')

# ✅ Works for prompts
prompts = client.get_prompts(filters='tags contains "production"')
```

## Best Practices

1. **Use tags for categorization**: Use tags to categorize your prompts effectively
2. **Combine with name filtering**: Use the `name` parameter for simple name-based filtering
3. **Handle errors gracefully**: Always catch `ValueError` when using filters
4. **Test your filters**: Verify your filter syntax works before using in production
5. **Use appropriate operators**: Choose the right operator for your field type (e.g., `>` for numeric fields)
