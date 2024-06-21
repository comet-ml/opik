#!/usr/bin/env python
# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at http://www.comet.ml
#  Copyright (C) 2015-2021 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

from pathlib import Path

from setuptools import find_packages, setup

requirements = [
    "comet_ml>=3.43.0",
    "dataclasses; python_version<'3.7.0'",
    "flatten-dict",
    "requests",
    "types-requests",
]
project_urls = {"Source code": "https://github.com/comet-ml/comet-llm"}
this_directory = Path(__file__).parent
long_description = (this_directory / ".github" / "PACKAGE_README.md").read_text(
    encoding="utf-8"
)


setup(
    author="Comet ML Inc.",
    author_email="mail@comet.com",
    python_requires=">=3.6",
    classifiers=[
        "Development Status :: 2 - Pre-Alpha",
        "Intended Audience :: Developers",
        "Natural Language :: English",
        "Programming Language :: Python :: 3 :: Only",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.6",
        "Programming Language :: Python :: 3.7",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3",
        "License :: OSI Approved :: MIT License",
    ],
    description="Comet logger for LLM",
    install_requires=requirements,
    long_description=long_description,
    long_description_content_type="text/markdown",
    include_package_data=True,
    keywords="comet_llm",
    name="comet_llm",
    packages=find_packages("src"),
    package_dir={"": "src"},
    url="https://www.comet.com",
    project_urls=project_urls,
    version="2.2.5",
    zip_safe=False,
    license="MIT",
)
