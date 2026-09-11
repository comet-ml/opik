.DEFAULT_GOAL := help

.PHONY: help clean build dev lint test

help:
	@echo "Python SDK Docs Makefile targets"
	@echo "  make build  - Build HTML docs into build/html"
	@echo "  make dev    - Run sphinx-autobuild for local preview"
	@echo "  make lint   - Run strict Sphinx validation (-nW)"
	@echo "  make test   - Alias for lint"
	@echo "  make clean  - Remove build artifacts"

clean:
	rm -rf build

build: clean
	sphinx-build -b html source build/html

dev: clean
	sphinx-autobuild source build/html

lint: clean
	sphinx-build -nW --keep-going -b html source build/html

test: lint
