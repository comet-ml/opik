import os
import sys

# Put the tests/ dir on the path so both suites can `from llm_constants import ...`
# regardless of whether pytest is invoked as `pytest tests/e2e` or `cd tests && pytest unit`.
sys.path.insert(0, os.path.dirname(__file__))
