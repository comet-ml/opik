import os
import subprocess
import tempfile


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
            with open(script_path, "w") as f:
                f.write("\n".join([*self.history, self.code]))

            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{os.path.dirname(self.pip_path)}:{env.get('PATH', '')}",
                    "VIRTUAL_ENV": self.env_path,
                    "PYTHONPATH": os.path.dirname(self.python_path),
                }
            )

            try:
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
