import types
import tqdm
import sys
from unittest.mock import patch
from opik.console_utils import (
    _in_colab_environment,
    _in_ipython_environment,
    _in_jupyter_environment,
    get_tqdm,
)


def make_ipython_module() -> types.ModuleType:
    module = types.ModuleType("IPython")
    module.get_ipython = lambda: None
    return module


class IPythonObject(object):
    def __init__(self, user_local=None, colab=False):
        self.kernel = True
        self.colab = colab
        if user_local is None:
            user_local = {"_ih": ["import IPython"], "_oh": {}}

        # save
        self.ns_table = {"user_local": user_local}

    def __repr__(self):
        if self.colab:
            return "<google.colab._shell.Shell object at 0x7f22f3a203d0>"
        else:
            return "<IPython.terminal.interactiveshell.TerminalInteractiveShell object at 0x7f41dfc7ca60>"


class TestConsoleUtils:
    @classmethod
    def tearDownClass(cls):
        if "IPython" in sys.modules:
            del sys.modules["IPython"]

    def test_colab_environment(self):
        sys.modules["IPython"] = IPython = make_ipython_module()
        assert _in_colab_environment() is False

        with patch.object(
            IPython, "get_ipython", side_effect=lambda: IPythonObject(colab=True)
        ):
            assert _in_colab_environment() is True

    def test_ipython_environment(self):
        sys.modules["IPython"] = IPython = make_ipython_module()

        assert _in_jupyter_environment() is False
        assert _in_ipython_environment() is False

        class FakeIPythonObject(object):
            pass

        # Simulate an ipython environment:
        with patch.object(
            IPython, "get_ipython", side_effect=lambda: FakeIPythonObject()
        ):
            assert _in_jupyter_environment() is False
            assert _in_ipython_environment() is True

    def test_jupyter_environment(self):
        sys.modules["IPython"] = IPython = make_ipython_module()

        assert _in_jupyter_environment() is False
        assert _in_ipython_environment() is False

        # Simulate a jupyter environment, something with a kernel
        with patch.object(IPython, "get_ipython", side_effect=lambda: IPythonObject()):
            assert _in_jupyter_environment() is True
            assert _in_ipython_environment() is True

    def test_get_tqdm(self):
        _tqdm = get_tqdm()

        assert _tqdm is tqdm.tqdm

    def test_get_tqdm_jupyter(self):
        sys.modules["IPython"] = IPython = make_ipython_module()

        with patch.object(IPython, "get_ipython", side_effect=lambda: IPythonObject()):
            _tqdm = get_tqdm()

        assert _tqdm is tqdm.tqdm_notebook

    def test_get_tqdm_colab(self):
        sys.modules["IPython"] = IPython = make_ipython_module()

        with patch.object(
            IPython,
            "get_ipython",
            side_effect=lambda: IPythonObject(colab=True),
        ):
            _tqdm = get_tqdm()

        assert _tqdm is tqdm.tqdm_notebook
