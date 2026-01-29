# Opik Python Backend

Opik Python Backend is a service that runs Python code in a sandboxed environment. While primarily prepared via Docker, it can also run in a spawned process (for development or non-restricted environments).

## Requirements

- Install Docker.
- Install Python.
- Create and enable a Python virtual environment.
- Install all dependencies from `requirements.txt`.
- For running tests, also install dependencies from `tests/test_requirements.txt`.
- Check the important environment variables:
    - `PYTHON_CODE_EXECUTOR_STRATEGY`: sets backend to use Docker containers (use 'docker' or subprocesses (use 'process', or empty as it's the default))
    - `PYTHON_CODE_EXECUTOR_PARALLEL_NUM`: number of containers or subprocesses to use (default: 5)
    - `PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS`: timeout for execution in seconds (default: 3)
    - `PYTHON_CODE_EXECUTOR_ALLOW_NETWORK`: set to `true` to allow network access in executor containers (default: false)
    - `PYTHON_CODE_EXECUTOR_CPU_SHARES`: Docker CPU shares for containers, higher = higher priority (default: 512, Docker default is 1024)
    - `PYTHON_CODE_EXECUTOR_MEM_LIMIT`: Memory limit for executor containers, uses Docker format with single letter unit b/k/m/g (default: 256m)

## Running the Flask service

> [!TIP]
> Run it in debug mode for development purposes, it reloads the code automatically.

- From `apps/opik-python-backend` directory.
- Run the `opik_backend` module.
- Debug mode is enabled with `--debug`.

```bash
flask --app src/opik_backend --debug run
```

Service is reachable at: `http://localhost:5000`
