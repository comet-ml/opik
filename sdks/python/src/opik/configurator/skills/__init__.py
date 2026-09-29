from typing import List

from opik.configurator.skills import roots as skills_roots
from opik.configurator.skills.install import setup_skills

__all__ = [
    "detected_host_keys",
    "setup_skills",
]


def detected_host_keys() -> List[str]:
    return skills_roots.detected_host_keys()
