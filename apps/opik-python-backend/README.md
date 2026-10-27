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
    - `PYTHON_CODE_EXECUTOR_PROCESS_NETWORK_ISOLATION`: set to `true` to run each `process` strategy worker as an unprivileged user (uid 65534) with a minimal environment, under a seccomp filter that refuses creating sockets and inspecting other processes, so user code has no network access (default: false). Needs Linux and the python-backend container running as root with CAP_SETUID/CAP_SETGID (granted to root by default; no privileged mode or extra capability), and works under a container seccomp profile such as RuntimeDefault. If isolation can't be applied no worker starts and an error naming this setting and the failed step is logged. Any value other than `true` or `false` fails startup
    - `PYTHON_CODE_EXECUTOR_MAX_RESULT_SIZE_IN_BYTES`: largest result a `process` strategy worker may return, as JSON; a longer result fails that evaluation. A positive integer; any other value fails startup (default: 67108864, i.e. 64 MiB)
    - `PYTHON_CODE_EXECUTOR_CPU_SHARES`: Docker CPU shares for containers, higher = higher priority (default: 512, Docker default is 1024)
    - `PYTHON_CODE_EXECUTOR_MEM_LIMIT`: Memory limit for executor containers, uses Docker format with single letter unit b/k/m/g (default: 256m)
    - `PYTHON_CODE_EXECUTOR_CPU_LIMIT`: Hard CPU limit per executor container in fractional CPUs, e.g. "0.5" = half a core (default: unset, no hard limit)
    - `PYTHON_CODE_EXECUTOR_METRICS_INTERVAL_IN_SECONDS`: Interval for collecting executor container CPU/memory metrics via Docker stats API (default: 60)

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
