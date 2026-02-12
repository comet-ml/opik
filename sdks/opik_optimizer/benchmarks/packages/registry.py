from __future__ import annotations

from benchmarks.packages.hotpot.package import HotpotPackage
from benchmarks.packages.package import BenchmarkPackage, PackageResolution
from benchmarks.packages.simple import SimpleDatasetPackage

_PACKAGES: list[BenchmarkPackage] = [
    HotpotPackage(),
    SimpleDatasetPackage(key="hover", prefixes=("hover",)),
    SimpleDatasetPackage(key="ifbench", prefixes=("ifbench",)),
    SimpleDatasetPackage(key="pupa", prefixes=("pupa",)),
]


def resolve_package(dataset_name: str) -> PackageResolution | None:
    for package in _PACKAGES:
        if package.matches(dataset_name):
            return PackageResolution(key=package.key, package=package)
    return None


def list_packages() -> list[str]:
    return sorted({pkg.key for pkg in _PACKAGES})
