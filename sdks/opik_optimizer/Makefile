# Simple task shortcuts for local development.

VENV ?= .venv
PYTHON ?= $(VENV)/bin/python
PIP ?= $(PYTHON) -m pip
PRECOMMIT ?= $(VENV)/bin/pre-commit

.DEFAULT_GOAL := help

.PHONY: help setup-venv install install-dev test precommit build clean

help:
	@echo "Available targets:"
	@echo "  help         Show this help message (default)"
	@echo "  setup-venv   Create virtual environment in $(VENV)"
	@echo "  install      Install package"
	@echo "  install-dev  Install package with dev dependencies"
	@echo "  test         Run tests"
	@echo "  precommit    Run pre-commit hooks on all files"
	@echo "  build        Build sdist and wheel"
	@echo "  clean        Remove build artifacts and virtualenv"

setup-venv:
	python3 -m venv $(VENV)

install:
	$(PIP) install .

install-dev:
	$(PIP) install .[dev]
	# $(PRECOMMIT) install

# Disable LiteLLM disk cache to avoid RO sqlite errors during tests.
test:
	PYTHONPATH=src LITELLM_CACHE_TYPE=memory $(PYTHON) -m pytest --cov=opik_optimizer --cov-report=term-missing --cov-report=xml --cov-report=html

precommit:
	PYTHONPATH=src $(PRECOMMIT) run --all-files

build:
	$(PYTHON) setup.py sdist bdist_wheel

clean:
	rm -rf build dist *.egg-info
	rm -rf $(VENV)
