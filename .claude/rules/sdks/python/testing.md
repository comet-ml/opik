# Python SDK Testing Rules

When creating or modifying tests under `sdks/python/tests/`, follow the conventions in `.agents/skills/python-sdk/testing.md`:

## Test Naming Convention

Use the pattern: `test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT`

```python
# Standard test
def test_tracked_function__error_inside_inner_function__caught_in_top_level_span():

# Happy path
def test_optimization_lifecycle__happyflow():
```

Note the **double underscores** separating the three parts.

## Test Infrastructure

- Use `fake_backend` fixture for integration tests that create traces/spans.
- Use `verifiers` from `tests.e2e` for E2E tests with actual API calls.
- Use `ANY_BUT_NONE`, `ANY_STRING`, and `assert_equal` from `tests.testlib` for flexible assertions.

## Key Rules

- Always test **public API only**.
- Study existing similar tests before adding new ones.