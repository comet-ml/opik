from typing import List

from opik.configurator.skills import roots as skills_roots
from opik.configurator.skills.install import (
    setup_skills,
    uninstall_skills,
    update_skills,
)

__all__ = [
    "detected_host_keys",
    "detected_host_names",
    "setup_skills",
    "uninstall_skills",
    "update_skills",
]


def detected_host_keys() -> List[str]:
    return skills_roots.detected_host_keys()


def detected_host_names() -> List[str]:
    """Display names of the AI hosts the skill pack can be installed for here."""
    return skills_roots.display_names(skills_roots.detected_host_keys())
