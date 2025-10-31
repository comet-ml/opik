# Simple task shortcuts for local development.

VENV ?= .venv
VENV_PY := $(VENV)/bin/python
VENV_PRECOMMIT := $(VENV)/bin/pre-commit

ifeq ($(wildcard $(VENV_PY)),)
PYTHON ?= python3
PIP ?= $(PYTHON) -m pip
PRECOMMIT ?= pre-commit
else
PYTHON ?= $(VENV_PY)
PIP ?= $(PYTHON) -m pip
PRECOMMIT ?= $(VENV_PRECOMMIT)
endif

define warn_if_no_venv
@if [ ! -f "$(VENV_PY)" ]; then \
  echo "[warn] Virtual environment $(VENV) not found; using system-level interpreters/tools."; \
fi
endef

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
	$(call warn_if_no_venv)
	$(PIP) install .

install-dev:
	$(call warn_if_no_venv)
	# $(PRECOMMIT) install
	$(PIP) install '.[dev]'

# Disable LiteLLM disk cache to avoid RO sqlite errors during tests.
test:
	$(call warn_if_no_venv)
	PYTHONPATH=src LITELLM_CACHE_TYPE=memory $(PYTHON) -m pytest --cov=opik_optimizer --cov-report=term-missing --cov-report=xml --cov-report=html

precommit:
	$(call warn_if_no_venv)
	PYTHONPATH=src $(PRECOMMIT) run --all-files

build:
	$(call warn_if_no_venv)
	$(PYTHON) setup.py sdist bdist_wheel

clean:
	rm -rf build dist *.egg-info
	rm -rf $(VENV)
