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

The documentation website is built using [Fern](https://www.buildwithfern.com/) and is located in `apps/opik-documentation/documentation`.

In order to run the documentation website locally, you need to have `npm` installed. Once installed, you can run the documentation locally using the following command:

```bash
cd apps/opik-documentation/documentation

# Install dependencies - Only needs to be run once
npm install

# Run the documentation website locally
npm run dev
```

You can then access the documentation website at `http://localhost:3000`. Any change you make to the documentation will be updated in real-time.

When updating the documentation, you will need to update either:

- `docs/cookbook`: This is where all our cookbooks are located.
- `fern/docs`: This is where all the markdown code is stored and where the majority of the documentation is located.


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

In order to develop features in the Python SDK, you will need to have Opik running locally. You can follow the instructions in the [Configuring your development environment](#configuring-your-development-environment) section or by running Opik locally with Docker Compose:

```bash
cd deployment/docker-compose

# Starting the Opik platform
docker compose up --detach

# Configure the Python SDK to point to the local Opik deployment
opik configure --use_local
```

The Opik server will be running on `http://localhost:5173`.

**Submitting a PR:**

First, please read the [coding guidelines](sdks/python/README.md) for our Python SDK

The Python SDK is available under `sdks/python` and can be installed locally using `pip install -e sdks/python`.

Before submitting a PR, please ensure that your code passes the test suite:

```bash
cd sdks/python

pytest tests/
```

and the linter:

```bash
cd sdks/python

pre-commit run --all-files
```

> [!NOTE]
> If you changes impact public facing methods or docstrings, please also update the documentation. You can find more information about updating the docs in the [documentation contribution guide](#contributing-to-the-documentation).

### Contributing to the frontend

The Opik frontend is a React application that is located in `apps/opik-frontend`.

In order to run the frontend locally, you need to have `npm` installed. Once installed, you can run the frontend locally using the following command:

```bash
# Run the backend locally with the flag "--local-fe"
./build_and_run.sh --local-fe

cd apps/opik-frontend

# Install dependencies - Only needs to be run once
npm install

# Run the frontend locally
npm run start
```

You can then access the development frontend at `http://localhost:5173/`. Any change you make to the frontend will be updated in real-time.

> You will need to open the FE using `http://localhost:5173/` ignoring the output from the `npm run start` command which will recommend to open `http://localhost:5174/`. In case `http://localhost:5174/` is opened, the BE will not be accessible.

Before submitting a PR, please ensure that your code passes the test suite, the linter and the type checker:

```bash
cd apps/opik-frontend

npm run e2e
npm run lint
npm run typecheck
```

### Contributing to the backend

In order to run the external services (Clickhouse, MySQL, Redis), you can use the `build_and_run.sh` script or `docker-compose`:

```bash
cd deployment/docker-compose

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
