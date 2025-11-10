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
        # - Cap at 1.77.4: version 1.77.5+ removes trace_id/parent_span_id passthrough
        #   Can be removed after this PR is released: https://github.com/BerriAI/litellm/pull/15529
        # - Cap at 1.77.1: version 1.77.2+ introduces C++ compiler dependency. It's already fixed in litellm
        #   but we can't remove the cap because we still have to cap at 1.77.4.
        "litellm<=1.77.1,!=1.75.0,!=1.75.1,!=1.75.2,!=1.75.3,!=1.75.4,!=1.75.5",
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
