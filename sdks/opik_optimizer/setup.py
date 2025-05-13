from setuptools import setup, find_packages

setup(
    name="opik_optimizer",
    version="0.7.3",
    description="Agent optimization with Opik",
    author="Comet ML",
    author_email="support@comet.com",
    long_description=open("README.md", encoding="utf-8").read(),
    long_description_content_type='text/markdown',
    url="https://github.com/comet-ml/opik",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    python_requires=">=3.9,<3.13",
    install_requires=[
        "opik>=1.7.17",
        "dspy>=2.6.18,<3",
        "litellm",
        "tqdm",
        "datasets",
        "optuna",
        "pydantic",
        "pandas",
        "hf_xet",
        "pyrate-limiter",
    ],
    # dev requirements
    extras_require={
        "dev": [
            "adalflow",
            "pytest",
            "pytest-conv"
        ],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
    ],
)
