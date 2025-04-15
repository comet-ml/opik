from setuptools import setup, find_packages

setup(
    name="opik_backend",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
)
