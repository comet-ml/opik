import contextlib
import importlib
import os
import sys
import types
from typing import Any, Dict, List


@contextlib.contextmanager
def patch_environ(
    add_keys: Dict[str, Any],
    remove_keys: List[str] = None,
):
    """
    Temporarily set environment variables inside the context manager and
    fully restore the previous environment afterward
    """
    original_env = {key: os.getenv(key) for key in add_keys}

    for key in remove_keys or []:
        if key in os.environ:
            original_env[key] = os.getenv(key)
            del os.environ[key]

    os.environ.update(add_keys)

    try:
        yield
    finally:
        for key, value in original_env.items():
            if value is None:
                del os.environ[key]
            else:
                os.environ[key] = value


def patch_submodule(
    monkeypatch: Any,
    dotted_name: str,
    replacement: types.ModuleType,
) -> None:
    """
    Install `replacement` in place of `dotted_name` so that every import form sees it.

    Patching `sys.modules` alone is not enough for a submodule. Both
    `import a.b.c as x` and `from a.b import c` bind through the *parent package's
    attribute*, which the import system sets the first time the real submodule is
    imported anywhere in the process and which `monkeypatch.setitem` never touches.
    A `sys.modules`-only stub therefore works while nothing has imported the real
    submodule and is silently bypassed once something has — which makes the test
    relying on it depend on whichever tests ran before it in the same process.
    """
    monkeypatch.setitem(sys.modules, dotted_name, replacement)

    parent_name, _, attribute_name = dotted_name.rpartition(".")
    if parent_name:
        parent_module = importlib.import_module(parent_name)
        monkeypatch.setattr(parent_module, attribute_name, replacement, raising=False)
