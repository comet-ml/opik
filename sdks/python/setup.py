import os

from setuptools import find_packages, setup

project_urls = {"Source code": "https://github.com/comet-ml/opik"}

HERE = os.path.abspath(os.path.dirname(__file__))
version = os.environ.get("VERSION")
if version is None:
    version_file = os.path.join(HERE, "..", "..", "version.txt")
    if os.path.exists(version_file):
        with open(version_file) as fp:
            version = fp.read().strip()
    else:
        version = "0.0.1"

setup(
    author="Comet ML Inc.",
    author_email="mail@comet.com",
    python_requires=">=3.10",
    classifiers=[
        "Development Status :: 2 - Pre-Alpha",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: Apache Software License",
        "Natural Language :: English",
        "Programming Language :: Python :: 3 :: Only",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Programming Language :: Python :: 3.13",
        "Programming Language :: Python :: 3.14",
    ],
    description="Comet tool for logging and evaluating LLM traces",
    long_description=open(
        os.path.join(HERE, "..", "..", "README.md"), encoding="utf-8"
    ).read(),
    long_description_content_type="text/markdown",
    install_requires=[
        "boto3-stubs[bedrock-runtime]>=1.34.110",
        "click",
        "httpx",  # some older version of openai/litellm are broken with httpx>=0.28.0
        "rapidfuzz>=3.0.0,<4.0.0",
        # LiteLLM dependency comments:
        # Please keep this list in sync with the one in sdks/opik_optimizer/pyproject.toml
        # - Exclude 1.82.7, 1.82.8: compromised in supply chain attack (TeamPCP)
        #   See: https://docs.litellm.ai/blog/security-update-march-2026
        # - Exclude 1.81.*, 1.82.*, 1.83.0-1.83.6: CVE-2026-42208 (SQL injection in proxy auth path,
        #   affects 1.81.16-1.83.6, fixed in 1.83.7).
        #   See: https://docs.litellm.ai/blog/cve-2026-42208-litellm-proxy-sql-injection
        "litellm>=1.79.2,!=1.81.*,!=1.82.*,!=1.83.0,!=1.83.1,!=1.83.2,!=1.83.3,!=1.83.4,!=1.83.5,!=1.83.6",
        "openai",
        "pydantic-settings>=2.0.0,<3.0.0,!=2.9.0",
        "pydantic>=2.0.0,<3.0.0",
        "pytest",
        "rich",
        "sentry_sdk>=2.0.0",
        "tenacity",
        "tqdm",
        "uuid6",
        "jinja2",
        "watchfiles>=1.0.0,<2.0.0",
        # tree-sitter is used for JS/TS syntax checking in bridge handlers.
        # Pre-built wheels are missing for musllinux_aarch64 (Alpine on ARM64),
        # and PEP 508 has no marker to distinguish musl from glibc, so we
        # exclude all Linux aarch64 to avoid a source-build failure on Alpine.
        # Affected glibc aarch64 users can manually:
        #   pip install tree-sitter tree-sitter-javascript \
        #     tree-sitter-typescript
        (
            "tree-sitter>=0.23,<1.0;"
            " platform_machine != 'aarch64'"
            " or sys_platform != 'linux'"
        ),
        (
            "tree-sitter-javascript>=0.23,<1.0;"
            " platform_machine != 'aarch64'"
            " or sys_platform != 'linux'"
        ),
        (
            "tree-sitter-typescript>=0.23,<1.0;"
            " platform_machine != 'aarch64'"
            " or sys_platform != 'linux'"
        ),
    ],
    extras_require={
        "proxy": [
            "fastapi>=0.100.0",
            "uvicorn>=0.23.0",
        ],
    },
    entry_points={
        "pytest11": [
            "opik = opik.plugins.pytest.hooks",
        ],
        "console_scripts": ["opik = opik.cli:cli"],
    },
    keywords="opik",
    name="opik",
    include_package_data=True,
    package_data={"opik": ["py.typed"]},
    packages=find_packages("src"),
    package_dir={"": "src"},
    url="https://www.comet.com",
    project_urls=project_urls,
    version=version,
    zip_safe=False,
    license="Apache 2.0 License",
)
