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

### CI/CD Configuration

For CI/CD environments, make sure to set up the required environment variables as secrets in your CI/CD system. The environment variables should be added to your workflow configuration and passed to the test environment.

Example GitHub Actions workflow configuration:

```yaml
env:
  OPIK_TEST_ENV: staging
  OPIK_API_KEY: ${{ secrets.OPIK_API_KEY }}
  OPIK_TEST_USER_EMAIL: ${{ secrets.OPIK_TEST_USER_EMAIL }}
  OPIK_TEST_USER_PASSWORD: ${{ secrets.OPIK_TEST_USER_PASSWORD }}