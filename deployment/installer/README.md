# Opik Server Installer & Manager

The Opik server installer is a Python package that installs and manages the
Opik server on a local machine.
It aims to make this process as simple as possible, by reducing the number of
steps required to install the Opik server.

## Usage

To install the tool, run the following command:

```bash
pip install opik-server
```

### Installing Opik Server

To install the Opik server, run the following command:

```bash
opik-server install
```

You can also run the installer in debug mode to see the details of the
installation process:

```bash
opik-server --debug install
```

By default, the installer will install the same version of the Opik as its
own version (`opik-server -v`). If you want to install a specific version, you
can specify the version using the `--opik-version` flag:

```bash
opik-server install --opik-version 0.1.0
```

By default, the installer will setup a local port forward to the Opik server
using the port `5173`. If you want to use a different port, you can specify
the port using the `--local-port` flag:

```bash
opik-server install --local-port 5174
```

### Upgrading Opik Server

To upgrade the Opik server, run the following command:

```bash
pip install --upgrade opik-server
opik-server upgrade
```

Or upgrade to a specific version:

```bash
opik-server upgrade --opik-version 0.1.1
```

## Building the Python Package

To build the package:

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

## QA Testing

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

### Uninstalling

To uninstall the Opik server, run the following command:

```bash
minikube delete
```

To reset the machine to a clean state, with no Opik server installed, it is
best to use a fresh VM. But if you want to reset the machine to a clean state
without reinstalling the VM, you can run the following commands:

#### macOS

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

#### Ubuntu

```bash
minikube delete
sudo apt-get remove helm kubectl minikube docker-ce containerd.io
rm -rf ~/.minikube
rm -rf ~/.helm
rm -rf ~/.kube
rm -rf ~/.docker
```
