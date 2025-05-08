from setuptools import setup, find_packages

setup(
    name="opik_optimizer",
    version="0.7.0",
    description="Agent optimization with Opik",
    author="Comet ML",
    author_email="info@comet.ml",
    url="https://github.com/comet-ml/opik",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    python_requires=">=3.9",
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
