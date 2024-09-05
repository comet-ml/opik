from setuptools import find_packages, setup
import os

project_urls = {"Source code": "https://github.com/comet-ml/opik"}

setup(
    author="Comet ML Inc.",
    author_email="mail@comet.com",
    python_requires=">=3.8",
    classifiers=[
        "Development Status :: 2 - Pre-Alpha",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: Apache Software License",
        "Natural Language :: English",
        "Programming Language :: Python :: 3 :: Only",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
    ],
    description="Comet tool for logging and evaluating LLM traces",
    long_description=open("./../../README.md", encoding="utf-8").read(),
    long_description_content_type="text/markdown",
    install_requires=[
        "httpx<1.0.0",
        "langchain_community<1.0.0",
        "langchain_openai<1.0.0",
        "levenshtein~=0.25.1",
        "openai<2.0.0",
        "pandas>=2.0.0,<3.0.0",
        "pydantic-settings>=2.0.0,<3.0.0",
        "pydantic>=2.0.0,<3.0.0",
        "pytest",
        "tqdm",
        "uuid7<1.0.0",
        "rich",
    ],
    entry_points={
        "pytest11": [
            "opik = opik.plugins.pytest.hooks",
        ]
    },
    include_package_data=True,
    keywords="opik",
    name="opik",
    packages=find_packages("src"),
    package_dir={"": "src"},
    url="https://www.comet.com",
    project_urls=project_urls,
    version=os.environ.get("VERSION", "0.0.1"),
    zip_safe=False,
    license="Apache 2.0 License",
)
