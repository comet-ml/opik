import os
import subprocess
import sys

import pytest


def test_disabled_mode():
    worker_script = os.path.join(os.path.dirname(__file__), "helper_script.py")
    process = subprocess.Popen(["python", worker_script])
    return_code = process.wait()

    assert return_code == 0

