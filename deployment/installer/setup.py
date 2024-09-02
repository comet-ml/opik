# setup.py
# type: ignore
import os
from setuptools import setup

project_urls = {"Source code": "https://github.com/comet-ml/opik"}

setup(
    version=os.environ.get('VERSION', '0.0.1'),
    long_description=open('README.md',encoding="utf-8").read(),
    long_description_content_type='text/markdown',
)
