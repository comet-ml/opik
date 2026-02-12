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

.PHONY: help setup-venv install install-dev test test-all precommit build clean
.PHONY: modal-worker modal-coord modal-deploy modal-logs modal-list modal-check-results
.PHONY: benchmark-local benchmark-modal

help:
	@echo "Available targets:"
	@echo "  help         Show this help message (default)"
	@echo "  setup-venv   Create virtual environment in $(VENV)"
	@echo "  install      Install package"
	@echo "  install-dev  Install package with dev dependencies"
	@echo "  test         Run tests"
	@echo "  test-all     Run tests including integration markers"
	@echo "  profile      Profile unit tests (cProfile via pytest-profiling)"
	@echo "  precommit    Run pre-commit hooks on all files"
	@echo "  build        Build sdist and wheel"
	@echo "  clean        Remove build artifacts and virtualenv"
	@echo "  benchmark-local Run benchmarks locally via unified runner"
	@echo "  benchmark-modal Run benchmarks on Modal via unified runner"
	@echo "  modal-worker Deploy Modal worker (benchmarks/engines/modal/engine.py)"
	@echo "  modal-coord  Deploy Modal coordinator (benchmarks/run_benchmark_modal.py)"
	@echo "  modal-deploy Deploy worker + coordinator"
	@echo "  modal-logs   Tail worker app logs (env=main by default)"
	@echo "  modal-list   Show engine/dataset/optimizer registry (local)"
	@echo "  modal-check-results   Show remote results from Modal volume (requires run-id, set RUN_ID=...)"

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
	HF_HUB_ENABLE_PROGRESS_BARS=0 \
	HF_HUB_DISABLE_TELEMETRY=1 \
	PYTHONPATH=src \
	LITELLM_CACHE_TYPE=memory \
	LITELLM_LOGGING_DISABLED=1 \
	LITELLM_USE_QUEUE_LOGGING=false \
	$(PYTHON) -m pytest \
		-n auto \
		--dist loadfile \
		-vv -rA \
		--maxfail=1 \
		--cov=opik_optimizer \
		--cov-report=term-missing \
		--cov-report=xml \
		--cov-report=html \
		-m "not integration and not e2e" \
		tests/unit

test-all:
	$(call warn_if_no_venv)
	HF_HUB_ENABLE_PROGRESS_BARS=0 \
	HF_HUB_DISABLE_TELEMETRY=1 \
	PYTHONPATH=src \
	LITELLM_CACHE_TYPE=memory \
	LITELLM_LOGGING_DISABLED=1 \
	LITELLM_USE_QUEUE_LOGGING=false \
	$(PYTHON) -m pytest \
		-n auto \
		--dist loadfile \
		-vv -rA \
		--maxfail=1 \
		--cov=opik_optimizer \
		--cov-report=term-missing \
		--cov-report=xml \
		--cov-report=html \
		-m "integration or e2e or not integration" \
		tests tests/e2e

profile:
	$(call warn_if_no_venv)
	HF_HUB_OFFLINE=1 \
	HF_DATASETS_OFFLINE=1 \
	HF_HUB_ENABLE_PROGRESS_BARS=0 \
	HF_HUB_DISABLE_TELEMETRY=1 \
	PYTHONPATH=src \
	LITELLM_CACHE_TYPE=memory \
	LITELLM_LOGGING_DISABLED=1 \
	LITELLM_USE_QUEUE_LOGGING=false \
	$(PYTHON) -m pytest -n auto --dist loadfile tests/unit -vv -rA --profile --maxfail=1

precommit:
	$(call warn_if_no_venv)
	PYTHONPATH=src $(PRECOMMIT) run --all-files

build:
	$(call warn_if_no_venv)
	$(PYTHON) setup.py sdist bdist_wheel

clean:
	@echo "Cleaning build artifacts and cache files..."
	@rm -rf build dist
	@find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name ".pytest_cache" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name ".mypy_cache" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name ".ruff_cache" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name ".cache" -exec rm -rf {} + 2>/dev/null || true
	@find . -type d -name ".litellm_cache" -exec rm -rf {} + 2>/dev/null || true
	@find . -type f -name "*.pyc" -delete 2>/dev/null || true
	@find . -type f -name "*.pyo" -delete 2>/dev/null || true
	@find . -type f -name ".coverage*" -delete 2>/dev/null || true
	@rm -rf .hf_home
	@rm -rf .hf_datasets
	@rm -rf .hf_cache
	@rm -rf htmlcov coverage.xml
	@rm -rf $(VENV)
	@echo "Clean complete."

modal-worker:
	@echo "Deploying Modal worker..."
	modal deploy benchmarks/engines/modal/engine.py

modal-coord:
	@echo "Deploying Modal coordinator..."
	modal deploy benchmarks/run_benchmark_modal.py

modal-deploy: modal-worker modal-coord

modal-logs:
	@echo "Tailing Modal worker logs (env=main)..."
	modal app logs opik-optimizer-benchmarks --env main

modal-list:
	@echo "Listing engine/dataset/optimizer registry (local)..."
	PYTHONPATH=src $(PYTHON) benchmarks/run_benchmark.py --list-registries

modal-check-results:
	@echo "Checking Modal results for run $(RUN_ID)..."
	modal run benchmarks/check_results.py --run-id $(RUN_ID)

benchmark-local:
	@echo "Running benchmarks locally via unified runner..."
	PYTHONPATH=src $(PYTHON) benchmarks/run_benchmark.py --engine local

benchmark-modal:
	@echo "Running benchmarks on Modal via unified runner..."
	PYTHONPATH=src $(PYTHON) benchmarks/run_benchmark.py --engine modal
