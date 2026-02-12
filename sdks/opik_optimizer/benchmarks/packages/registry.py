from __future__ import annotations

from benchmarks.packages.hover.package import HoverPackage
from benchmarks.packages.hotpot.package import HotpotPackage
from benchmarks.packages.ifbench.package import IfbenchPackage
from benchmarks.packages.package import BenchmarkPackage, PackageResolution
from benchmarks.packages.pupa.package import PupaPackage

_PACKAGES: list[BenchmarkPackage] = [
    HotpotPackage(),
    HoverPackage(),
    IfbenchPackage(),
    PupaPackage(),
]


def resolve_package(dataset_name: str) -> PackageResolution | None:
    for package in _PACKAGES:
        if package.matches(dataset_name):
            return PackageResolution(key=package.key, package=package)
    return None


def list_packages() -> list[str]:
    return sorted({pkg.key for pkg in _PACKAGES})
