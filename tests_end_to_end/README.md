# End-to-End Tests

This directory contains end-to-end tests for the Opik application.

## Environment Configuration

The tests can be run against different environments (local or staging) using environment variables. The configuration system supports:

- Local development environment (default)
- Staging environment

### Environment Variables

The following environment variables are used to configure the test environment:

- `OPIK_TEST_ENV`: The environment to run tests against (`local` or `staging`). Defaults to `local`.
- `OPIK_API_KEY`: API key for authentication (required for staging environment)
- `OPIK_TEST_USER_EMAIL`: Test user email (required for staging environment)
- `OPIK_TEST_USER_PASSWORD`: Test user password (required for staging environment)

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
- API URL: http://localhost:5173/api
- Web URL: http://localhost:5173

#### Staging Environment

To run tests against the staging environment, you need to:

1. Set up the required environment variables:

```bash
export OPIK_TEST_ENV=staging
export OPIK_API_KEY=your_api_key
export OPIK_TEST_USER_EMAIL=test_user@example.com
export OPIK_TEST_USER_NAME=test_user
export OPIK_TEST_USER_PASSWORD=test_password
```

2. Run the tests:

```bash
pytest
```

This will use the staging configuration:
- API URL: https://staging.dev.comet.com/opik/api
- Web URL: https://staging.dev.comet.com/opik

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
```

### Running in Different Environments

You can combine environment configuration with specific test suites:

```bash
# Run sanity tests in staging
OPIK_TEST_ENV=staging \
OPIK_API_KEY=your_api_key \
OPIK_TEST_USER_EMAIL=test_user@example.com \
OPIK_TEST_USER_PASSWORD=test_password \
pytest -m sanity

# Run specific suite in staging
OPIK_TEST_ENV=staging \
OPIK_API_KEY=your_api_key \
OPIK_TEST_USER_EMAIL=test_user@example.com \
OPIK_TEST_USER_PASSWORD=test_password \
pytest tests/Projects/test_projects_crud_operations.py