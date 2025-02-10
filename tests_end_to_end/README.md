# End-to-End Tests

This directory contains end-to-end tests for the Opik application.

## Environment Configuration

The tests use environment variables for configuration. All settings can be customized through environment variables, with defaults provided for local development.

### Environment Variables

The following environment variables are used to configure the test environment:

- `OPIK_BASE_URL`: Base URL for the application (defaults to `http://localhost:5173`)
  - Web UI is accessed at this URL
  - API is accessed at `{base_url}/api`
- `OPIK_TEST_WORKSPACE`: Test workspace name (defaults to `default`)
- `OPIK_TEST_PROJECT_NAME`: Test project name (defaults to `automated_tests_project`)
- `OPIK_TEST_USER_EMAIL`: Test user email (required for non-local environments)
- `OPIK_TEST_USER_NAME`: Test user name (required for non-local environments)
- `OPIK_TEST_USER_PASSWORD`: Test user password (required for non-local environments)
- `OPIK_API_KEY`: API key (optional, used for non-local environments)

### Running Tests

#### Prerequisites

Before running any tests, make sure you're in the `tests_end_to_end` directory and set the PYTHONPATH:

```bash
cd tests_end_to_end
export PYTHONPATH='.'
```

This ensures that Python can find all the test modules and utilities correctly.

#### Local Environment (Default)

To run tests against your local development environment:

```bash
pytest
```

This will use the default configuration:
- Base URL: http://localhost:5173
  - Web UI: http://localhost:5173
  - API: http://localhost:5173/api

#### Non-Local Environments

To run tests against other environments, you need to:

1. Set up the required environment variables:

```bash
export OPIK_BASE_URL=https://your-environment.comet.com/opik
export OPIK_TEST_USER_EMAIL=test_user@example.com
export OPIK_TEST_USER_NAME=test_user
export OPIK_TEST_USER_PASSWORD=test_password
export OPIK_API_KEY=your_api_key  # if required
```

2. Run the tests:

```bash
pytest
```

### Test Suites

The tests are organized into different suites that can be run independently:

#### Running Specific Test Suites

```bash
# Run all tests
pytest

# Run only projects tests
pytest tests/Projects/test_projects_crud_operations.py

# Run all dataset tests
pytest tests/Datasets/

# Run traces tests
pytest tests/Traces/test_traces_crud_operations.py

# Run experiments tests
pytest tests/Experiments/test_experiments_crud_operations.py

# Run prompts tests
pytest tests/Prompts/test_prompts_crud_operations.py

# Run feedback definitions tests
pytest tests/FeedbackDefinitions/test_feedback_definitions_crud.py
```

#### Running Sanity Tests

To run only the sanity tests (marked with @pytest.mark.sanity):

```bash
pytest -m sanity
```

### Additional Options

You can combine different options when running tests:

```bash
# Run tests with verbose output
pytest -v

# Run tests with live logs
pytest -s

# Run specific test file with setup information
pytest tests/Projects/test_projects_crud_operations.py --setup-show

# Run tests with specific browser
pytest --browser chromium  # default
pytest --browser firefox
pytest --browser webkit

# Combine multiple options
pytest -v -s tests/Datasets/ --browser firefox --setup-show
