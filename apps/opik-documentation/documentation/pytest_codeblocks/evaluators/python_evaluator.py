import os
import subprocess
import tempfile
import logging

LOGGER = logging.getLogger(__name__)


class PythonEvaluator:
    def __init__(self, code, start_line=None, history=None, timeout=60):
        self.code = code
        self.start_line = start_line
        self.history = history or []
        self.timeout = timeout

    def set_env(self, env_path: str, python_path: str, pip_path: str):
        self.env_path = env_path
        self.python_path = python_path
        self.pip_path = pip_path

    def evaluate(self):
        # Run the code in a subprocess
        with tempfile.TemporaryDirectory() as temp_dir:
            script_path = os.path.join(temp_dir, "script.py")

            python_history = [
                x["content"] for x in self.history if x["language"] == "python"
            ]
            bash_history = [
                x["content"] for x in self.history if x["language"] == "bash"
            ]

            with open(script_path, "w") as f:
                f.write("\n".join([*python_history, self.code]))

            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{os.path.dirname(self.pip_path)}:{env.get('PATH', '')}",
                    "VIRTUAL_ENV": self.env_path,
                    "PYTHONPATH": os.path.dirname(self.python_path),
                }
            )

            try:
                for bash_command in bash_history:
                    subprocess.run(bash_command, shell=True, env=env)

                subprocess.run(
                    [self.python_path, script_path],
                    capture_output=True,
                    text=True,
                    env=env,
                    check=True,
                    timeout=self.timeout,
                )
            except subprocess.TimeoutExpired:
                raise TimeoutError(
                    f"Code execution timed out after {self.timeout} seconds"
                )
