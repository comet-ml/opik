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

To help us understand the issue you're experiencing, please provide steps to reproduce the issue included a minimal code snippet that reproduces the issue. This helps us diagnose the issue and fix it more quickly.

### Submitting a new feature request

Feature requests are welcome! To help us understand the feature you'd like to see, please provide:

1. A short description of the motivation behind this request
2. A detailed description of the feature you'd like to see, including any code snippets if applicable

If you are in a position to submit a PR for the feature, feel free to open a PR !

## Project set up and Architecture

The Opik project is made up of five main sub-projects:

* `apps/opik-documentation`: The Opik documentation website
* `deployment/installer`: The Opik installer
* `sdks/python`: The Opik Python SDK
* `apps/opik-frontend`: The Opik frontend application
* `apps/opik-backend`: The Opik backend server


In addition, Opik relies on:

1. Clickhouse: Used to trace traces, spans and feedback scores
2. MySQL: Used to store metadata associated with projects, datasets, experiments, etc.
3. Redis: Used for caching

#### Setting up the environment

The local development environment is based on `docker-compose`. 
Please see instructions in `deployment/docker-compose/README.md`

### Contributing to the documentation

The documentation is made up of two main parts:

1. `apps/opik-documentation/documentation`: The Opik documentation website
2. `apps/opik-documentation/python-sdk-docs`: The Python reference documentation

#### Contributing to the documentation website

The documentation website is built with [Fern](https://www.buildwithfern.com/) and is located in `apps/opik-documentation/documentation`.

In order to run the documentation website locally, you need to have Node.js and npm installed. You can follow this guide to install Node.js and npm [here](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm/).

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

In order to run the Python SDK reference documentation locally, you need to have `python` and `pip` installed. Once installed, you can run the documentation locally using the following command:

```bash
cd apps/opik-documentation/python-sdk-docs

# Install dependencies - Only needs to be run once
pip install -r requirements.txt

# Run the python sdk reference documentation locally
make dev
```

The Python SDK reference documentation will be built and available at `http://127.0.0.1:8000`. Any change you make to the documentation will be updated in real-time.

### Contributing to the Python SDK

**Setting up your development environment:**

In order to develop features in the Python SDK, you will need to have Opik running locally. You can follow the instructions in the [Docker Compose README](deployment/docker-compose/README.md) or use the provided script to start Opik:

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

For most SDK contributions, you should run the e2e tests which validate the core functionality:

```bash
cd sdks/python

# Install the test requirements
pip install -r tests/test_requirements.txt

# Install pre-commit for linting
pip install pre-commit

# Run the e2e tests
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
> If you changes impact public facing methods or docstrings, please also update the documentation. You can find more information about updating the docs in the [documentation contribution guide](#contributing-to-the-documentation).

### Contributing to the frontend

The Opik frontend is a React application that is located in `apps/opik-frontend`.

If you want to run the front-end locally and see your changes instantly on saving files, follow this guide:

#### Prerequisites

1. Ensure you have **Node.js** installed.

#### Steps

#### 1. Configure the Environment Variables

- Navigate to `apps/opik-frontend/.env.development` and update it with the following values:

  ```ini
  VITE_BASE_URL=/
  VITE_BASE_API_URL=http://localhost:8080
  ```

#### 2. Enable CORS in the Back-End

- Open `deployment/docker-compose/docker-compose.yaml` and in the `services.backend.environment` section,
  add `CORS: true` to allow cross-origin requests.

  It should look like this:

  ```yaml
  ...
  OPIK_USAGE_REPORT_ENABLED: ${OPIK_USAGE_REPORT_ENABLED:-true}
  CORS: true
  ...
  ```

#### 3. Start the Services

- Run the following command to start the necessary services and expose the required ports:

  ```bash
  # Optionally, you can force a pull of the latest images
  docker compose pull
  
  docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
  ```

#### 4. Verify the Back-End is Running

- Wait for the images to build and containers to start.
- To confirm that the back-end is running, open the following URL in your browser:

  ```
  http://localhost:8080/is-alive/ver
  ```

    - If you see a version number displayed, the back-end is running successfully.

#### 5. Install Front-End Dependencies

- Navigate to the front-end project directory:

  ```bash
  cd opik/apps/opik-frontend
  ```

- Install the necessary dependencies:

  ```bash
  npm install
  ```

#### 6. Start the Front-End

- Run the following command to start the front-end:

  ```bash
  npm run start
  ```

- Once the script completes, open your browser and go to:

  ```
  http://localhost:5174/
  ```

  You should see the app running! 🎉

### Notes:

- Another built front-end version will be available at `http://localhost:5173/`.  
  This version is used for checking builds, but you can also use it for the same purposes if needed.


- Before submitting a PR, please ensure that your code passes the test suite, the linter and the type checker:

  ```bash
  cd apps/opik-frontend
  
  npm run e2e
  npm run lint
  npm run typecheck
  ```

### Contributing to the backend

In order to run the external services (Clickhouse, MySQL, Redis), you can use `docker-compose`:

```bash
cd deployment/docker-compose

# Optionally, you can force a pull of the latest images
docker compose pull

docker compose up clickhouse redis mysql -d
```

#### Running the backend

The Opik backend is a Java application that is located in `apps/opik-backend`.

In order to run the backend locally, you need to have `java` and `maven` installed. Once installed, you can run the backend locally using the following command:

```bash
cd apps/opik-backend

# Build the Opik application
mvn clean install

# Start the Opik application
java -jar target/opik-backend-{project.pom.version}.jar server config.yml
```
Replace `{project.pom.version}` with the version of the project in the pom file.

Once the backend is running, you can access the Opik API at `http://localhost:8080`.

#### Formatting the code

Before submitting a PR, please ensure that your code is formatted correctly.
Run the following command to automatically format your code:

```bash
mvn spotless:apply
```

Our CI will check that the code is formatted correctly and will fail if it is not by running the following command:

```bash
mvn spotless:check
```

#### Testing the backend

Before submitting a PR, please ensure that your code passes the test suite:

```bash
cd apps/opik-backend

mvn test
```

Tests leverage the `testcontainers` library to run integration tests against a real instances of the external services. Ports are randomly assigned by the library to avoid conflicts.

#### Advanced usage

*Health Check*
To see your applications health enter url `http://localhost:8080/healthcheck`

**Run migrations**

*DDL migrations*

The project handles it using [liquibase](https://www.liquibase.com/). Such migrations are located at `apps/opik-backend/src/main/resources/liquibase/{{DB}}/migrations` and executed via `apps/opik-backend/run_db_migrations.sh`. This process is automated via Docker image and helm chart.

In order to run DB DDL migrations manually, you will need to run:
* Check pending migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} status config.yml`
* Run migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} migrate config.yml`
* Create schema tag `java -jar target/opik-backend-{project.pom.version}.jar {database} tag config.yml {tag_name}`
* Rollback migrations `java -jar target/opik-backend-{project.pom.version}.jar {database} rollback config.yml --count 1` OR `java -jar target/opik-backend-{project.pom.version}.jar {database} rollback config.yml --tag {tag_name}`

Replace `{project.pom.version}` with the version of the project in the pom file. Replace `{database}` with db for MySQL migrations and with `dbAnalytics` for ClickHouse migrations.

Requirements:
* Such migrations have to be backward compatible, which means:
    - New fields must be optional or have default values
    - In order to remove a column, all references to it must be removed at least one release before the column is dropped at the DB level.
    - Renaming the column is forbidden unless the table is not currently being used.
    - Renaming the table is forbidden unless the table is not currently being used.
    - For more complex migration, apply the transition phase. Refer to [Evolutionary Database Design](https://martinfowler.com/articles/evodb.html)
* It has to be independent of the code. 
* It must not cause downtime
* It must have a unique name
* It must contain a rollback statement or, in the case of Liquibase, the word `empty` is not possible. Refer to [link](https://docs.liquibase.com/workflows/liquibase-community/using-rollback.html)

*DML migrations*

In such cases, migrations will not run automatically. They have to be run manually by the system admin via the database client. These migrations are documented via `CHANGELOG.md` and placed at `apps/opik-backend/data-migrations` together with all instructions required to run them.

Requirements:
* Such migrations have to be backward compatible, which means:
    - Data shouldn't be deleted unless 100% safe
    - It must not prevent rollback to the previous version
    - It must not degrade performance after running
    - For more complex migration, apply the transition phase. Refer to [Evolutionary Database Design](https://martinfowler.com/articles/evodb.html)
* It must contain detailed instructions on how to run it
* It must be batched appropriately to avoid disrupting operations 
* It must not cause downtime
* It must have a unique name
* It must contain a rollback statement or, in the case of Liquibase, the word `empty` is not possible. Refer to [link](https://docs.liquibase.com/workflows/liquibase-community/using-rollback.html).

*Accessing Clickhouse*

You can curl the ClickHouse REST endpoint with `echo 'SELECT version()' | curl -H 'X-ClickHouse-User: opik' -H 'X-ClickHouse-Key: opik' 'http://localhost:8123/' -d @-`.

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
