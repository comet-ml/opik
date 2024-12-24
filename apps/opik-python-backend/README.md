# Opik Python Backend

## Requirements

- Install Python: at least the minimum version compatible with the Opik Python SDK.
- Create and enable a Python virtual environment.
- Install all dependencies from `requirements.txt`.
- For running tests, also install dependencies from `tests/test_requirements.txt`.

## Running the Flask service

> [!TIP]
> Run in debug mode for development purposes, it reloads the code automatically.

- From `apps/opik-python-backend` directory.
- Run the `opik_backend` module.
- Debug mode is enabled with `--debug`.

```bash
flask --app src/opik_backend --debug run
```

Service is reachable at: `http://localhost:5000`
