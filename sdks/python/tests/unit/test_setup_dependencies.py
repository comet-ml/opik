"""Guard the split between compatibility bounds and cooldown caps in setup.py.

The cooldown caps (`name<=X`) are published in the opt-in `cooldown` extra. If
one slips back into `install_requires`, it binds every project that installs
opik and blocks newer releases of that library until opik raises the cap.
"""

import ast
import pathlib
from typing import Dict, List

from packaging.requirements import Requirement
from packaging.utils import canonicalize_name

SETUP_PY = pathlib.Path(__file__).resolve().parents[2] / "setup.py"


def _setup_keyword(name: str) -> ast.expr:
    tree = ast.parse(SETUP_PY.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "setup":
            for keyword in node.keywords:
                if keyword.arg == name:
                    return keyword.value
    raise AssertionError(f"setup() has no `{name}` argument")


def _install_requires() -> List[Requirement]:
    value = ast.literal_eval(_setup_keyword("install_requires"))
    return [Requirement(requirement) for requirement in value]


def _extras_require() -> Dict[str, List[Requirement]]:
    value = ast.literal_eval(_setup_keyword("extras_require"))
    return {
        extra: [Requirement(requirement) for requirement in requirements]
        for extra, requirements in value.items()
    }


def _is_cooldown_cap(requirement: Requirement) -> bool:
    """A cooldown cap is a requirement made of a single `<=` bound and nothing else."""
    specifiers = list(requirement.specifier)
    return len(specifiers) == 1 and specifiers[0].operator == "<="


def test_install_requires__has_no_cooldown_caps():
    caps = [str(r) for r in _install_requires() if _is_cooldown_cap(r)]

    assert caps == [], (
        "Cooldown caps must live in extras_require['cooldown'], not install_requires: "
        f"{caps}"
    )


def test_cooldown_extra__only_contains_cooldown_caps():
    cooldown = _extras_require()["cooldown"]

    not_caps = [str(r) for r in cooldown if not _is_cooldown_cap(r)]

    assert not_caps == [], (
        f"Only `name<=X` caps belong in the cooldown extra: {not_caps}"
    )


def test_cooldown_extra__every_cap_has_a_dependency_in_install_requires():
    # A cap on its own still installs the library, so a cap whose library was
    # removed from install_requires would keep installing it for opik[cooldown].
    dependencies = {canonicalize_name(r.name) for r in _install_requires()}

    orphans = [
        r.name
        for r in _extras_require()["cooldown"]
        if canonicalize_name(r.name) not in dependencies
    ]

    assert orphans == [], f"Caps without a dependency in install_requires: {orphans}"
