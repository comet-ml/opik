import os
import subprocess


class BashEvaluator:
    def __init__(self, code, file=None, start_line=None, test=True):
        self.code = code
        self.file = file
        self.start_line = start_line
        self.test = test

    def set_env(self, env_path: str, python_path: str, pip_path: str):
        self.env_path = env_path
        self.python_path = python_path
        self.pip_path = pip_path

    def evaluate(self):
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{os.path.dirname(self.pip_path)}:{env.get('PATH', '')}",
                "VIRTUAL_ENV": self.env_path,
                "PYTHONPATH": os.path.dirname(self.python_path),
            }
        )

        subprocess.run(self.code, shell=True, env=env)
