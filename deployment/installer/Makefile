.PHONY: clean-pkg
clean-pkg:
	@rm -rf build dist *.egg-info

.PHONY: clean
clean: clean-pkg

.PHONY: deps
deps:
	@pip install -r dev-requirements.txt

.PHONY: dependencies
dependencies: deps
