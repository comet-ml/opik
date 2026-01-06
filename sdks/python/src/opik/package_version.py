import importlib.metadata


def _get_package_version() -> str:
    try:
        return importlib.metadata.version("opik")
    except importlib.metadata.PackageNotFoundError:
        # Fallback to setuptools-scm generated version file for development
        try:
            from opik._version import version  # noqa: PLC0415

            return version
        except ImportError:
            return "0.0.0+unknown"


VERSION = _get_package_version()
