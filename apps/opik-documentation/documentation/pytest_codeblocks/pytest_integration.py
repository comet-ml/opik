import pytest
import logging
from pytest import Collector, ExceptionInfo, Module, Session
from _pytest._code.code import TerminalRepr
from typing import Union, Optional, Iterator, Any
from pathlib import Path
import os
from . import evaluators, reporting, parsing_utils, environment

LOGGER = logging.getLogger(__name__)


class OpikDocsTestFile(pytest.File):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.venv_path = None
        self.venv_python = None
        self.venv_pip = None

    @classmethod
    def from_parent(
        cls, parent: Session, path: Path, **kwargs: Any
    ) -> "OpikDocsTestFile":
        item = super().from_parent(parent=parent, path=path, **kwargs)

        return item

    def collect(self) -> Iterator["OpikDocsTestItem"]:
        code_blocks = parsing_utils.get_code_blocs(self.path)

        for code_block in code_blocks:
            if not self.venv_path:
                LOGGER.info("Setting up venv for code snippets in:", self.path)
                default_packages = self.config.getoption("--default-package")
                self.venv_path, self.venv_python, self.venv_pip = environment.setup_env(
                    default_packages
                )

            code_block.set_env(
                env_path=self.venv_path,
                python_path=self.venv_python,
                pip_path=self.venv_pip,
            )

            yield OpikDocsTestItem.from_parent(
                parent=self,
                name=f"code_block_starting_line_{code_block.start_line}",
                test_case=code_block,
            )


class OpikDocsTestItem(pytest.Item):
    def __init__(
        self,
        name,
        parent,
        test_case: Optional[
            Union[evaluators.PythonEvaluator, evaluators.BashEvaluator]
        ] = None,
    ):
        super().__init__(name, parent)
        self.test_case = test_case

    @classmethod
    def from_parent(cls, parent, name, test_case, **kwargs):
        item = super().from_parent(parent=parent, name=name, **kwargs)
        item.test_case = test_case
        return item

    def runtest(self):
        if self.test_case is not None:
            self.test_case.evaluate()

    def repr_failure(
        self, excinfo: ExceptionInfo[BaseException]
    ) -> Union[str, TerminalRepr]:
        return reporting.format_error(self.fspath, self.test_case, excinfo)


def pytest_addoption(parser):
    """Pytest hook to add custom options for code block testing.

    Adds the following options:
        --default-package: Specify packages to be installed in the test environment.
                          Can be provided multiple times to install multiple packages.
                          Defaults to ["opik"] if not specified.
    """
    parser.addoption(
        "--default-package",
        action="append",
        default=[],
        help="Default package to install in test environments. Can be specified multiple times.",
    )


def pytest_collect_file(parent: Collector, file_path: Path) -> Optional[Module]:
    """Hook to collect MDX files for testing"""
    test_path = parent.config.args[0] if parent.config.args else None

    if test_path:
        # Convert both paths to absolute and normalized form for comparison
        test_path = os.path.abspath(os.path.normpath(test_path))
        current_path = os.path.abspath(os.path.normpath(str(file_path)))

        # Only collect if this is the specific file being tested
        if current_path.startswith(test_path) and file_path.suffix in (".mdx", ".md"):
            return OpikDocsTestFile.from_parent(parent=parent, path=file_path)
    elif file_path.suffix in (".mdx", ".md"):
        # Fallback to old behavior if no specific path provided
        return OpikDocsTestFile.from_parent(parent=parent, path=file_path)
    return None
