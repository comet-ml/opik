# Opik Installer

This python package provides a wrapper around an Ansible Playbook that sets up
Docker + Minikube and installs the Open Source Opik Server using Helm.

## Building the Package

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

### Versioning

The version of the package is set dynamically by the `setup.py` file using the
[`git-semver-compute` script](https://github.com/comet-ml/git-semver-compute/blob/main/calculate-version.sh).
This script dynamically calculates the version of the package based on the
latest git tag (that is a valid SemVer) and the number of commits since that
tag. This ensures versions are always correctly incremented.

Because of this, it is important to ensure that when building a release, the
latest tag is a valid SemVer tag, and that no changes are made to the repo's
tacked files as a side effect of the package build.

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
opik-server install \
    --helm-repo-url https://helm.comet.com \
    --helm-repo-username cometml \
    --helm-repo-password <REQUEST FROM DEPLOYMENT> \
    --container-registry ghcr.io \
    --container-registry-username cometml \
    --container-registry-password <REQUEST FROM DEPLOYMENT> \
    --container-repo-prefix comet-ml/opik \
    --opik-version 0.0.356
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
