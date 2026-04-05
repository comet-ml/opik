# CONNECT-001: Bridge command type casing mismatch — all bridge commands fail

## Severity
Critical (blocking — no bridge commands can work)

## Test Case
T2.1

## Steps to Reproduce
1. Start runner: `opik connect --pair CODE python3 simple_agent.py`
2. Submit bridge command: `POST /v1/private/local-runners/{runnerId}/bridge/commands` with `{"type":"ReadFile","args":{"path":"simple_agent.py"}}`
3. Poll result: `GET /v1/private/local-runners/{runnerId}/bridge/commands/{commandId}`

## Expected Behavior
Command completes with file content.

## Actual Behavior
Command fails with:
```json
{
  "status": "failed",
  "error": {
    "code": "unknown_type",
    "message": "Unknown command type: ReadFile"
  }
}
```

## Root Cause
The backend Java enum `BridgeCommandType` serializes as PascalCase (`ReadFile`, `WriteFile`, `EditFile`, `ListFiles`, `SearchFiles`), but the Python supervisor handler map in `supervisor.py:79-84` uses snake_case keys:

```python
handlers = {
    "read_file": ReadFileHandler(...),
    "write_file": WriteFileHandler(...),
    "edit_file": EditFileHandler(...),
    "list_files": ListFilesHandler(...),
    "search_files": SearchFilesHandler(...),
}
```

When `bridge_loop.py:171` does `handler = self._handlers.get(command_type)`, it gets `None` because `"ReadFile" != "read_file"`.

## Fix Options
1. Change Python handler keys to PascalCase to match backend
2. Convert the incoming type to snake_case before lookup (e.g. `re.sub(r'(?<!^)(?=[A-Z])', '_', command_type).lower()`)
3. Change Java enum serialization to snake_case

## Environment
- Branch: collinc/bridge
- Files: `sdks/python/src/opik/runner/supervisor.py:79-84`, `apps/opik-backend/src/main/java/com/comet/opik/api/runner/BridgeCommandType.java:14-18`
