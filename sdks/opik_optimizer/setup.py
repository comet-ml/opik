from setuptools import find_packages, setup

setup(
    name="opik_optimizer",
    version="1.0.0",
    description="Agent optimization with Opik",
    author="Comet ML",
    author_email="support@comet.com",
    long_description=open("README.md", encoding="utf-8").read(),
    long_description_content_type="text/markdown",
    url="https://github.com/comet-ml/opik",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    package_data={
        "opik_optimizer": ["data/*.json"],
    },
    python_requires=">=3.9,<3.13",
    install_requires=[
        "datasets",
        "deap>=1.4.3",
        "diskcache",
        "hf_xet",
        "litellm",
        "opik>=1.7.17",
        "optuna",
        "pandas",
        "pydantic",
        "pyrate-limiter",
        "tqdm",
    ],
    # dev requirements
    extras_require={
        "dev": ["pytest", "pytest-conv"],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
    ],
)
