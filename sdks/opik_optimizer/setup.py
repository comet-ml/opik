from setuptools import setup, find_packages

setup(
    name="opik_optimizer",
    version="0.6.0",
    description="Agent optimization with Opik",
    author="Comet ML",
    author_email="info@comet.ml",
    url="https://github.com/comet-ml/opik",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    python_requires=">=3.9",
    install_requires=[
        "opik",
        "dspy",
        "tqdm",
    ],
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
    ],
)
