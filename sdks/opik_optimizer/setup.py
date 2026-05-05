from setuptools import find_packages, setup

setup(
    name="opik_optimizer",
    description="Agent optimization with Opik",
    author="Comet ML",
    author_email="support@comet.com",
    license="Apache 2.0",
    long_description=open("README.md", encoding="utf-8").read(),
    long_description_content_type="text/markdown",
    url="https://github.com/comet-ml/opik",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    package_data={
        "opik_optimizer": ["data/*.json", "data/*.jsonl"],
    },
    python_requires=">=3.10,<3.15",
    install_requires=[
        "datasets",
        "deap>=1.4.3",
        "hf_xet",
        # LiteLLM dependency comments:
        # - Exclude litellm 1.75.0-1.75.5 (broken callbacks system)
        # - Exclude versions 1.77.2-1.77.4: introduce C++ compiler dependency (madoka), fixed in 1.77.5
        #   See: https://github.com/BerriAI/litellm/issues/14762
        # - Exclude versions 1.77.5-1.79.1: remove trace_id/parent_span_id passthrough, fixed in 1.79.2+
        #   See: https://github.com/BerriAI/litellm/pull/15529
        # - Exclude 1.81.x: HTTP client closed during concurrent calls
        #   See: https://github.com/BerriAI/litellm/issues/19608
        # - Exclude 1.82.7, 1.82.8: compromised in supply chain attack (TeamPCP)
        #   See: https://docs.litellm.ai/blog/security-update-march-2026
        # - Exclude 1.82.*, 1.83.0-1.83.6: CVE-2026-42208 (SQL injection in proxy auth path,
        #   affects 1.81.16-1.83.6, fixed in 1.83.7).
        #   See: https://docs.litellm.ai/blog/cve-2026-42208-litellm-proxy-sql-injection
        # Please keep this list in sync with the one in sdks/opik_optimizer/pyproject.toml
        "litellm>=1.79.2,!=1.75.0,!=1.75.1,!=1.75.2,!=1.75.3,!=1.75.4,!=1.75.5,!=1.77.3,!=1.77.4,!=1.77.5,!=1.77.7,!=1.78.0,!=1.78.2,!=1.78.3,!=1.78.4,!=1.78.5,!=1.78.6,!=1.78.7,!=1.79.0,!=1.79.1,!=1.81.*,!=1.82.*,!=1.83.0,!=1.83.1,!=1.83.2,!=1.83.3,!=1.83.4,!=1.83.5,!=1.83.6",
        "opik>=1.7.17",
        "optuna",
        "pandas",
        "pydantic",
        "pyrate-limiter",
        "tqdm",
        "rich",
    ],
    # dev requirements and optional dependencies
    extras_require={
        "dev": [
            "pytest",
            "pytest-cov",
            # "google-adk",
            "langgraph",
            "gepa>=0.0.7",
        ],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Programming Language :: Python :: 3.13",
        "Programming Language :: Python :: 3.14",
    ],
)
