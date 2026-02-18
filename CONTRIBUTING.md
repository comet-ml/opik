# Contributing to Opik

We're excited that you're interested in contributing to Opik! There are many ways to contribute, from writing code to improving the documentation.

The easiest way to get started is to:

* Submit [bug reports](https://github.com/comet-ml/opik/issues) and [feature requests](https://github.com/comet-ml/opik/issues)
* Review the documentation and submit [Pull Requests](https://github.com/comet-ml/opik/pulls) to improve it
* Speaking or writing about Opik and [letting us know](https://chat.comet.com)
* Upvoting [popular feature requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22feature+request%22) to show your support
* Review our [Contributor License Agreement](https://github.com/comet-ml/opik/blob/main/CLA.md)


## Submitting a new issue or feature request

### Submitting a new issue

Thanks for taking the time to submit an issue, it's the best way to help us improve Opik!

Before submitting a new issue, please check the [existing issues](https://github.com/comet-ml/opik/issues) to avoid duplicates.

To help us understand the issue you're experiencing, please provide steps to reproduce the issue and include a minimal code snippet that reproduces it. This helps us diagnose the issue and fix it more quickly.

### Submitting a new feature request

Feature requests are welcome! To help us understand the feature you'd like to see, please provide:

1. A short description of the motivation behind this request
2. A detailed description of the feature you'd like to see, including any code snippets if applicable

If you are in a position to submit a PR for the feature, feel free to open a PR!

## Contribution Checklist

Use this before opening a PR:

1. Open or confirm an issue/feature request and link it in your PR description (`Fixes #...` or `Resolves #...`).
2. Pick the right component path (`apps/`, `sdks/`, `tests_end_to_end/`) and follow its local setup.
3. Run format/lint checks in your touched area.
4. Run relevant tests before pushing.
5. Add/adjust docs when behavior, API, or public-facing CLI/docs strings change.

## General Contribution Guidelines

### Code Formatting and Style

When contributing to Opik, please follow these formatting and code style guidelines:

#### Avoid Excessive Formatting Changes

**Do not apply formatting changes that drastically alter files without clear benefit.** When submitting PRs:

- **Focus on meaningful changes**: Only format code that you're actively modifying or fixing
- **Avoid mass reformatting**: Don't run formatters across entire files or codebases unless specifically requested
- **Be intentional**: If you need to apply formatting changes, ensure they serve a clear purpose (e.g., fixing consistency issues, improving readability)

#### When Formatting is Appropriate

- Fixing inconsistent indentation in code you're modifying
- Correcting style violations in files you're actively working on
- Following project-specific linting rules (see component-specific sections below)
- Addressing formatting issues flagged by CI/CD pipelines

#### When to Avoid Formatting

- Applying auto-formatters to entire files when only changing a few lines
- Changing whitespace, line endings, or indentation across large portions of unchanged code
- Reformatting files just because your editor suggests it
- Making stylistic changes that don't align with the existing codebase patterns

> **Remember**: Code reviews should focus on logic, functionality, and meaningful improvements. Excessive formatting changes can obscure the actual purpose of your contribution and make reviews more difficult.

## Project set up and Architecture

The Opik project includes multiple key sub-projects:

* `apps/opik-documentation`: The Opik documentation website
* `deployment/installer`: The Opik installer
* `sdks/python`: The Opik Python SDK
* `apps/opik-frontend`: The Opik frontend application
* `apps/opik-backend`: The Opik backend server
* `apps/opik-ai-backend`: AI/backend utilities and integrations
* `apps/opik-guardrails-backend`: Guardrails service
* `apps/opik-python-backend`: Python backend utilities
* `apps/opik-sandbox-executor-python`: Python sandbox execution service


In addition, Opik relies on:

1. ClickHouse: Used to trace traces, spans and feedback scores
2. MySQL: Used to store metadata associated with projects, datasets, experiments, etc.
3. Redis: Used for caching

## Local Development Setup

We provide multiple development modes optimized for different workflows:

### Quick Start Guide

| Mode | Use Case | Command | Speed |
|------|----------|---------|-------|
| **Docker Mode** | Full stack testing, closest to production | `./opik.sh --build` | Slow |
| **Local Process** | Fast BE + FE development with hot reload | `scripts/dev-runner.sh` | Fast |
| **BE-Only** | Backend development only | `scripts/dev-runner.sh --be-only-restart` | Fast |
| **Infrastructure** | Manual with IDE development | `./opik.sh --infra --port-mapping` | Medium |

### Docker Mode (Full Stack)

Best for testing the complete system or when you need an environment closest to production.

```bash
# Build and start all services (first time setup)
./opik.sh --build

# On Windows
.\opik.ps1 --build

# Start without rebuilding (faster for subsequent runs)
./opik.sh

# Check service health
./opik.sh --verify

# Stop all services
./opik.sh --stop
```

Access the UI at http://localhost:5173.

### Local Process Mode (Recommended for Development)

Best for rapid development with instant code reloading. Runs backend and frontend as local processes.

```bash
# Full restart (stop, build, start) - use this for first time or after major changes
scripts/dev-runner.sh

# On Windows
scripts\dev-runner.ps1

# Start without rebuilding (faster when no dependency changes)
scripts/dev-runner.sh --start

# Check status
scripts/dev-runner.sh --verify

# View logs
scripts/dev-runner.sh --logs
```

Access the UI at http://localhost:5174 (Vite dev server with hot reload).

#### Testing on Mobile Devices

The local development server supports testing from mobile devices on the same network:

1. **Find your computer's IP address:**
   ```bash
   # macOS/Linux
   ipconfig getifaddr en0  # or ifconfig
   
   # Windows
   ipconfig  # Look for IPv4 Address
   ```

2. **Access from your mobile device:**
   ```
   http://YOUR_COMPUTER_IP:5174
   ```
   Example: `http://192.168.1.100:5174`

3. **How it works:**
   - The Vite dev server listens on `0.0.0.0` (all network interfaces)
   - A proxy automatically forwards `/api/*` requests to the backend
   - No configuration or hardcoded IP addresses needed
   - Works from any device on your local network

**Security Note:** Only use on trusted networks (home/office WiFi, not public WiFi)

### BE-Only Mode (Backend Development)

Best for backend-focused work. Frontend runs in Docker, backend as a local process.

```bash
# Start BE-only mode
scripts/dev-runner.sh --be-only-restart

# On Windows
scripts\dev-runner.ps1 --be-only-restart
```

Access the UI at http://localhost:5173.

### Additional Commands

```bash
# Build backend only
scripts/dev-runner.sh --build-be

# Build frontend only
scripts/dev-runner.sh --build-fe

# Run database migrations
scripts/dev-runner.sh --migrate

# Lint code
scripts/dev-runner.sh --lint-be
scripts/dev-runner.sh --lint-fe

# Enable debug logging
scripts/dev-runner.sh --restart --debug
```

### Getting Help

For a complete list of available commands and options, use the `--help` flag:

```bash
# Linux/Mac
./opik.sh --help
scripts/dev-runner.sh --help

# Windows
.\opik.ps1 --help
scripts\dev-runner.ps1 --help
```

For comprehensive documentation on local development, including troubleshooting, advanced usage, and workflow examples, see our [Local Development Guide](apps/opik-documentation/documentation/fern/docs/contributing/local-development.mdx).

### AI Editor Configuration

Opik provides AI coding rules for editors like Cursor and Claude Code. These are stored in `.agents/` and synced using the Makefile:

```bash
# For Cursor - creates .cursor symlink to .agents/
make cursor

# For Claude Code - syncs rules to .claude/
make claude

# Install git pre-commit hooks
make hooks
```

### Contributing to the documentation

The documentation is made up of two main parts:

1. `apps/opik-documentation/documentation`: The Opik documentation website
2. `apps/opik-documentation/python-sdk-docs`: The Python reference documentation

#### Contributing to the documentation website

The documentation website is built with [Fern](https://www.buildwithfern.com/) and is located in `apps/opik-documentation/documentation`.

To run the documentation website locally, you need Node.js and npm installed. You can follow this guide to install Node.js and npm [here](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm/).

Once installed, you can run the documentation locally using the following command:

```bash
cd apps/opik-documentation/documentation

# Install dependencies - Only needs to be run once
npm install

# Run the documentation website locally
npm run dev
```

You can then access the documentation website at `http://localhost:3000`. Any change you make to the documentation will be updated in real-time.

When updating the documentation, you will need to update either:

- `fern/docs`: This is where all the markdown code is stored and where the majority of the documentation is located.
- `docs/cookbook`: This is where all our cookbooks are located.

#### Contributing to the Python SDK reference documentation

The Python SDK reference documentation is built using [Sphinx](https://www.sphinx-doc.org/en/master/) and is located in `apps/opik-documentation/python-sdk-docs`.

To run the Python SDK reference documentation locally, you need `python` and `pip` installed. Once installed, you can run the documentation locally using the following command:

```bash
cd apps/opik-documentation/python-sdk-docs

# Install dependencies - Only needs to be run once
pip install -r requirements.txt

# Run the Python SDK reference documentation locally
make dev
```

The Python SDK reference documentation will be built and available at `http://127.0.0.1:8000`. Any change you make to the documentation will be updated in real-time.

### Contributing to the Python SDK

**Setting up your development environment:**

To develop features in the Python SDK, you need Opik running locally. Use the provided scripts to start the appropriate services:

On Linux or Mac:
```bash
# From the root of the repository
./opik.sh

# Configure the Python SDK to point to the local Opik deployment
opik configure --use_local
```

On Windows:
```powershell
# From the root of the repository
powershell -ExecutionPolicy ByPass -c ".\opik.ps1"

# Configure the Python SDK to point to the local Opik deployment
opik configure --use_local
```

The Opik server will be running on `http://localhost:5173`.

**Note for Windows users:**
- If Python is installed at system level, make sure `C:\Users\<name>\AppData\Local\Programs\Python<version>\Scripts\` is added to your PATH for the `opik` command to work after installation, and restart your terminal.
- It's recommended to use a virtual environment:
  ```powershell
  # Create a virtual environment
  py -m venv <environment_name>
  
  # Activate the virtual environment
  cd <environment_name>\Scripts && .\activate.bat
  
  # Install the SDK
  pip install -e sdks/python
  
  # Configure the SDK
  opik configure --use_local
  ```

**Submitting a PR:**

First, please read the [coding guidelines](sdks/python/README.md) for our Python SDK.

The Python SDK is available under `sdks/python` and can be installed locally using `pip install -e sdks/python`.

**Testing your changes:**

For most SDK contributions, run unit tests first and then e2e tests for end-to-end validation of core flows:

```bash
cd sdks/python

# Install the test requirements
pip install -r tests/test_requirements.txt
pip install -r tests/unit/test_requirements.txt

# Install pre-commit for linting
pip install pre-commit

# Run unit tests (baseline), then e2e tests (full workflow validation)
pytest tests/unit
pytest tests/e2e
```

If you're making changes to specific integrations (openai, anthropic, etc.):
1. Install the integration-specific requirements:
   ```bash
   # Example for OpenAI integration
   pip install -r tests/integrations/openai/requirements.txt
   ```
2. Configure any necessary API keys for the integration
3. Run the specific integration tests:
   ```bash
   # Example for OpenAI integration
   pytest tests/integrations/openai
   ```

Before submitting a PR, please ensure that your code passes the linter:

```bash
cd sdks/python
pre-commit run --all-files
```

> [!NOTE]
> If your changes impact public-facing methods or docstrings, please also update the documentation. You can find more information about updating the docs in the [documentation contribution guide](#contributing-to-the-documentation).

### Contributing to the frontend

The Opik frontend is a React application located in `apps/opik-frontend`.

#### Prerequisites

1. Ensure you have **Node.js** installed.

#### Quick Start

For rapid development with hot reload, use the local process mode:

```bash
# Linux/Mac - restart everything
scripts/dev-runner.sh

# Or just start (faster if already built)
scripts/dev-runner.sh --start

# Windows
scripts\dev-runner.ps1
```

Access the UI at http://localhost:5174 (Vite dev server with hot reload).

#### Alternative Setup Methods

You can also use:
- **Docker Mode**: `./opik.sh --build` for a complete Docker-based environment
- **Manual Setup**: `./opik.sh --backend --port-mapping` then manually start the frontend

For detailed setup instructions for each mode, see our [Frontend Contribution Guide](apps/opik-documentation/documentation/fern/docs/contributing/frontend.mdx).

#### Code Formatting

Before submitting a PR, ensure your code passes all checks:

```bash
# Linting (recommended)
scripts/dev-runner.sh --lint-fe  # Linux/Mac
scripts\dev-runner.ps1 --lint-fe  # Windows

# Or manually
cd apps/opik-frontend
npm run lint
npm run typecheck # TypeScript type checking
npm run deps:validate # Dependency architecture validation
```

#### Dependency Architecture

The frontend uses [dependency-cruiser](https://github.com/sverweij/dependency-cruiser) to enforce architectural boundaries. Run `npm run deps:validate` before committing component changes.

**Layer hierarchy (one-way imports only):**
```
ui → shared → pages-shared → pages
```

Key rules:
- **No circular dependencies** - Components must not create import cycles
- **Layer isolation** - Lower layers cannot import from higher layers
- **Plugin isolation** - Project code cannot import from plugins (only PluginsStore can)
- **API isolation** - API layer cannot import React components (except `use-toast.ts`)

If `deps:validate` fails with a NEW violation:
1. Refactor to follow the layer hierarchy
2. Move shared code to the appropriate layer
3. Extract utilities to `lib/` folder

See `.dependency-cruiser-known-violations.README.md` for existing violations that need fixing.

#### Testing

```bash
cd apps/opik-frontend
npm run test # Unit tests for utilities and helpers
```

### Contributing to the backend

The Opik backend is a Java application located in `apps/opik-backend`.

#### Prerequisites

1. Ensure you have **Java** and **Maven** installed.

#### Quick Start

For rapid backend development with a local process:

```bash
# Linux/Mac - restart everything
scripts/dev-runner.sh --be-only-restart

# Or just start (faster if already built)
scripts/dev-runner.sh --be-only-start

# Windows
scripts\dev-runner.ps1 --be-only-restart
```

Access the backend API at http://localhost:8080.

#### Alternative Setup Methods

You can also use:
- **Docker Mode**: `./opik.sh --build` for a complete Docker-based environment
- **Manual Setup**: `./opik.sh --infra --port-mapping` then manually build and start the backend

For detailed setup instructions for each mode, see our [Backend Contribution Guide](apps/opik-documentation/documentation/fern/docs/contributing/backend.mdx).

#### Code Formatting and Testing

Before submitting a PR, ensure your code is formatted. Our CI will check and fail if it is not formatted. Use the following command:

```bash
# Code formatting (recommended)
scripts/dev-runner.sh --lint-be  # Linux/Mac
scripts\dev-runner.ps1 --lint-be  # Windows

# Or manually
cd apps/opik-backend
mvn spotless:apply
```

#### Testing

```
# Run tests
mvn test
```

Tests leverage the `testcontainers` library to run integration tests against real instances of external services. Ports are randomly assigned by the library to avoid conflicts.

#### Health checks
To check your application's health, open `http://localhost:8080/healthcheck`.

#### Database Migrations

To run database migrations:

```bash
# Using dev-runner (recommended)
scripts/dev-runner.sh --migrate  # Linux/Mac
scripts\dev-runner.ps1 --migrate  # Windows

# Or manually
cd apps/opik-backend
java -jar target/opik-backend-*.jar db migrate config.yml          # MySQL
java -jar target/opik-backend-*.jar dbAnalytics migrate config.yml # ClickHouse
```

For detailed information on migrations, health checks, and advanced topics, see our [Backend Contribution Guide](apps/opik-documentation/documentation/fern/docs/contributing/backend.mdx).

#### Accessing ClickHouse

You can curl the ClickHouse REST endpoint with `echo 'SELECT version()' | curl -H 'X-ClickHouse-User: opik' -H 'X-ClickHouse-Key: opik' 'http://localhost:8123/' -d @-.`

```
SHOW DATABASES

Query id: a9faa739-5565-4fc5-8843-5dc0f72ff46d

┌─name───────────────┐
│ INFORMATION_SCHEMA │
│ opik               │
│ default            │
│ information_schema │
│ system             │
└────────────────────┘

5 rows in set. Elapsed: 0.004 sec. 
```

Sample result: `23.8.15.35`
