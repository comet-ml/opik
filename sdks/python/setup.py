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
    python_requires=">=3.9",
    classifiers=[
        "Development Status :: 2 - Pre-Alpha",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: Apache Software License",
        "Natural Language :: English",
        "Programming Language :: Python :: 3 :: Only",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.9",
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
        # - Exclude litellm 1.75.0-1.75.5 (broken callbacks system)
        # - Exclude versions 1.77.2-1.77.4: introduce C++ compiler dependency (madoka), fixed in 1.77.5
        #   See: https://github.com/BerriAI/litellm/issues/14762
        # - Exclude versions 1.77.5-1.79.1: remove trace_id/parent_span_id passthrough, fixed in 1.79.2+
        #   See: https://github.com/BerriAI/litellm/pull/15529
        # - Exclude version 1.80.9: broken python 3.9 installation.
        #   See: https://github.com/BerriAI/litellm/issues/17701
        # Please keep this list in sync with the one in sdks/opik_optimizer/pyproject.toml
        "litellm>=1.79.2,!=1.75.0,!=1.75.1,!=1.75.2,!=1.75.3,!=1.75.4,!=1.75.5,!=1.77.3,!=1.77.4,!=1.77.5,!=1.77.7,!=1.78.0,!=1.78.2,!=1.78.3,!=1.78.4,!=1.78.5,!=1.78.6,!=1.78.7,!=1.79.0,!=1.79.1,!=1.80.9",
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
