# Contributing to Opik

We're excited that you're interested in contributing to Opik! There are many ways to contribute, from writing code to improving the documentation.

The easiest way to get started is to:

* Submit [bug reports](https://github.com/comet-ml/opik/issues) and [feature requests](https://github.com/comet-ml/opik/issues)
* Review the documentation and submit [Pull Requests](https://github.com/comet-ml/opik/pulls) to improve it
* Speaking or writing about Opik and [letting us know](https://chat.comet.com)
* Upvoting [popular feature requests](https://github.com/comet-ml/opik/issues?q=is%3Aissue+is%3Aopen+label%3A%22feature+request%22) to show your support


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

### Contributing to the documentation

The documentation is made up of three main parts:

1. `apps/opik-documentation/documentation`: The Opik documentation website
2. `apps/opik-documentation/python-sdk-docs`: The Python reference documentation
3. `apps/opik-documentation/rest-api-docs`: The REST API reference documentation

#### Contributing to the documentation website

The documentation website is built using [Docusaurus](https://docusaurus.io/) and is located in `apps/opik-documentation/documentation`.

In order to run the documentation website locally, you need to have `npm` installed. Once installed, you can run the documentation locally using the following command:

```bash
cd apps/opik-documentation/documentation

# Install dependencies - Only needs to be run once
npm install

# Run the documentation website locally
npm run start
```

You can then access the documentation website at `http://localhost:3000`. Any change you make to the documentation will be updated in real-time.

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

The Python SDK is available under `sdks/python` and can be installed locally using `pip install -e sdks/python`.

To test your changes locally, you can run Opik locally using `opik server install`.

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

### Contributing to the installer

The Opik server installer is a Python package that installs and manages the Opik server on a local machine. In order to achieve this, the installer relies on:

1. Minikube: Used to manage the Kubernetes cluster
2. Helm: Used to manage the Kubernetes charts
3. Ansible: Used to manage the installation of the Opik server

#### Building the package
In order to build the package:

1. Ensure that you have the necessary packaging dependencies installed:

```bash
pip install -r pub-requirements.txt
```

2. Run the following command to build the package:

```bash
python -m build --wheel
```

This will create a `dist` directory containing the built package.

3. You can now upload the package to the PyPi repository using `twine`:

```bash
twine upload dist/*
```

#### QA Testing

To test the installer, clone this repository onto the machine you want to
install the Opik server on and install the package using the following
commands:

```bash
# Make sure pip is up to date
pip install --upgrade pip

# Clone the repository
git clone git@github.com:comet-ml/opik.git

# You may need to checkout the branch you want to test
#   git checkout installer-pkg

cd opik/deployment/installer/

# Install the package
pip install .
```

If your pip installation path you may get a warning that the package is not
installed in your `PATH`. This is fine, the package will still work.
But you will need to call the fully qualified path to the executable.
Review the warning message to see the path to the executable.

```bash
# When the package is publically released none of these flags will be needed.
# and you will be able to simply run `opik-server install`
opik-server install --opik-version 0.1.0
```

This will install the Opik server on your machine.

By default this will hide the details of the installation process. If you want
to see the details of the installation process, you can add the `--debug`
flag just before the `install` command.

```bash
opik-server --debug install ........
```

If successful, the message will instruct you to run a kubectl command to
forward the necessary ports to your local machine, and provide you with the
URL to access the Opik server.

#### Uninstalling

To uninstall the Opik server, run the following command:

```bash
minikube delete
```

To reset the machine to a clean state, with no Opik server installed, it is
best to use a fresh VM. But if you want to reset the machine to a clean state
without reinstalling the VM, you can run the following commands:

##### macOS

```bash
minikube delete
brew uninstall minikube
brew uninstall helm
brew uninstall kubectl
brew uninstall --cask docker
rm -rf ~/.minikube
rm -rf ~/.helm
rm -rf ~/.kube
rm -rf ~/.docker
sudo find /usr/local/bin -lname '/Applications/Docker.app/*' -exec rm {} +
```

##### Ubuntu

```bash
minikube delete
sudo apt-get remove helm kubectl minikube docker-ce containerd.io
rm -rf ~/.minikube
rm -rf ~/.helm
rm -rf ~/.kube
rm -rf ~/.docker
```

### Contributing to the frontend

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

### Contributing to the backend

The Opik backend is a Java application that is located in `apps/opik-backend`.

In order to run the backend locally, you need to have `java` and `maven` installed. Once installed, you can run the backend locally using the following command:

```bash
cd apps/opik-backend

# Build the Opik application
mvn clean install

# Run the Opik application - 
java -jar target/opik-backend-{project.pom.version}.jar server config.yml
```
Replace `{project.pom.version}` with the version of the project in the pom file. 

Once the backend is running, you can access the Opik API at `http://localhost:8080`.

Before submitting a PR, please ensure that your code passes the test suite:

```bash
cd apps/opik-backend

mvn test
```
