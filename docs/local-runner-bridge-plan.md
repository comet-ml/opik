# Local Runner Bridge — Python Implementation Plan

## Overview

Convert `opik connect` from a simple `execvpe` wrapper into a two-layer supervisor that stays alive to handle bridge commands (file operations from OllieAssist) while managing the user's app as a child process.

**Outer process (supervisor):** heartbeat, bridge command polling, file watcher, child lifecycle.
**Inner process (child):** user's app, `@track(entrypoint=True)` registration, job polling/execution — unchanged from today.

## Fern-Generated API (already exists)

All REST client code is generated and ready:

| Method | Endpoint | Notes |
|--------|----------|-------|
| `runners.next_bridge_commands(runner_id, max_commands=)` | `POST /bridge/next` | Long-polls ~30s |
| `runners.report_bridge_result(runner_id, command_id, status=, result=, error=, duration_ms=)` | `POST /bridge/commands/{id}/result` | |
| `runners.heartbeat(runner_id, capabilities=)` | `POST /{runnerId}/heartbeats` | `capabilities` param added |

Types: `BridgeCommandBatchResponse`, `BridgeCommandItem`, `BridgeCommand`, `BridgeCommandStatus`.

---

## Stage 1: Supervisor + Bridge Loop

Everything needed to make `opik connect` a proper supervisor with bridge command polling. Commands return `not_implemented` until Stage 2 adds real handlers.

### Files to Modify

**`src/opik/cli/connect.py`** — Replace `os.execvpe()` call with Supervisor:

```python
supervisor = Supervisor(
    command=list(command),
    env=env,
    repo_root=Path.cwd(),
    runner_id=runner_id,
    api=api,
)
supervisor.run()  # blocks until SIGTERM/SIGINT
```

**`src/opik/runner/in_process_loop.py`** — Skip heartbeat thread when supervised:

In `run()`, check `os.environ.get("OPIK_SUPERVISED") == "true"`. If set, don't start `_heartbeat_loop` thread — the supervisor owns heartbeat. Job polling and execution unchanged.

**`setup.py`** — Add `watchfiles` to `install_requires`.

### Files to Create

**`src/opik/runner/supervisor.py`** — Main supervisor class.

```python
class Supervisor:
    def __init__(self, command, env, repo_root, runner_id, api): ...
    def run(self) -> None: ...
    def _start_child(self) -> subprocess.Popen: ...
    def _stop_child(self, graceful_timeout=10) -> int | None: ...
    def _restart_child(self, reason: str) -> None: ...
```

**`run()` lifecycle:**
1. Install signal handlers (SIGTERM, SIGINT → set shutdown event)
2. Start heartbeat thread (sends `capabilities: ["jobs", "bridge"]`)
3. Start bridge poll thread
4. Start file watcher thread
5. Start child process
6. Wait for shutdown event or child exit
7. On shutdown: stop file watcher, stop child (SIGTERM → wait 10s → SIGKILL), stop bridge loop, stop heartbeat
8. On unexpected child exit (code != 0): check stability guard, restart if stable

**Child process launch:**
- `subprocess.Popen(command, env=env, cwd=repo_root, stdout=None, stderr=None)`
- `stdout=None, stderr=None` — child inherits terminal (colors, TTY, stdin all work)
- Env includes `OPIK_RUNNER_MODE=true`, `OPIK_RUNNER_ID`, `OPIK_PROJECT_NAME`, `OPIK_SUPERVISED=true`
- `OPIK_SUPERVISED=true` tells the child's `InProcessRunnerLoop` to skip heartbeat

**Graceful shutdown:**
- Send SIGTERM to child
- `child.wait(timeout=10)`
- If still alive: SIGKILL
- Return exit code

**Restart logic:**
- Log reason ("file changed: src/agent.py" or "child exited with code 1")
- Stop child
- Debounce: skip if another restart occurred within 1s
- Start child

---

**`src/opik/runner/file_watcher.py`** — Watches repo for code changes.

```python
class FileWatcher:
    def __init__(self, repo_root, on_change, extensions, debounce_seconds=1.0): ...
    def run(self, shutdown_event) -> None: ...
```

- Uses `watchfiles` library (same as uvicorn `--reload`)
- Default extensions: `{".py", ".js", ".ts", ".yaml", ".json", ".toml"}`
- Default ignore: `{".git", "__pycache__", "*.pyc", ".venv", "node_modules"}`
- Respects `.gitignore` via watchfiles built-in support
- Debounces: collects changes for `debounce_seconds`, calls `on_change` once with set of paths
- `on_change` callback triggers `supervisor._restart_child(reason)`

---

**`src/opik/runner/stability_guard.py`** — Prevents infinite restart loops.

```python
class StabilityGuard:
    def __init__(self, max_crashes=3, window_seconds=30.0): ...
    def record_crash(self) -> None: ...
    def is_stable(self) -> bool: ...
    def reset(self) -> None: ...
```

- Tracks timestamps of recent crashes
- `is_stable()` returns False if `max_crashes` in `window_seconds`
- When unstable: supervisor stops restarting, logs error
- `reset()` called when child starts successfully

---

**`src/opik/runner/bridge_loop.py`** — Bridge polling thread.

```python
class BridgePollLoop:
    def __init__(self, api, runner_id, repo_root, handlers, shutdown_event): ...
    def run(self) -> None: ...
```

**Core loop:**
1. Long-poll `api.runners.next_bridge_commands(runner_id, max_commands=10)` with `request_options=RequestOptions(timeout_in_seconds=45)` (server blocks up to 30s)
2. Dispatch commands to thread pool — reads in parallel, writes serialized per file
3. Report each result individually via `api.runners.report_bridge_result()`
4. Exponential backoff on poll errors (1s → 30s, reset on success)
5. On 410 (evicted): set shutdown event, exit loop

**Result reporting retry:** 3 attempts with 1s/2s/4s backoff. On 409 (duplicate): swallow silently. On all retries exhausted: log error, continue (don't crash loop).

---

**`src/opik/runner/bridge_handlers.py`** — Handler protocol, mutation queue, error types, stubs.

```python
class BridgeCommandHandler(Protocol):
    def execute(self, args: dict[str, Any], timeout: float) -> dict[str, Any]: ...

class CommandError(Exception):
    def __init__(self, code: str, message: str): ...

class FileMutationQueue:
    def lock(self, path: Path) -> threading.Lock: ...
    # Per-file lock keyed by os.path.realpath()
    # Reads don't acquire lock
    # Writes to same file: serialized
    # Writes to different files: parallel

class StubHandler(BridgeCommandHandler):
    def execute(self, args, timeout):
        raise CommandError("not_implemented", "Command type not yet implemented")
```

### Implementation Order

1. `stability_guard.py` + tests
2. `file_watcher.py` + tests
3. `bridge_handlers.py` (protocol, stubs, mutation queue) + tests
4. `bridge_loop.py` + tests
5. `supervisor.py` + `connect.py` rewrite + tests
6. `InProcessRunnerLoop` heartbeat skip (`OPIK_SUPERVISED`) + test
7. Wire it all together — supervisor starts bridge loop, file watcher, heartbeat

### Tests

**`tests/unit/runner/test_supervisor.py`**

Child lifecycle:
- `test_start_child__launches_process` — Popen called with correct command, env, cwd
- `test_start_child__env_includes_supervised_flag` — `OPIK_SUPERVISED=true` in env
- `test_start_child__inherits_terminal` — stdout=None, stderr=None (not PIPE)
- `test_stop_child__sigterm_then_wait` — sends SIGTERM, waits, returns exit code
- `test_stop_child__sigkill_after_timeout` — child doesn't exit → SIGKILL
- `test_stop_child__already_dead__no_error` — no exception

Restart:
- `test_restart__stops_and_starts` — old child stopped, new child started
- `test_restart__debounce` — 3 triggers in 0.5s → 1 restart

Unexpected child exit:
- `test_child_exit__restarts_if_stable` — code 1, guard stable → restart
- `test_child_exit__stops_if_unstable` — 3 crashes in 10s → no restart
- `test_child_exit__exit_0__no_restart` — clean exit → no restart

Shutdown:
- `test_shutdown__sigterm__stops_all` — child stopped, threads stopped
- `test_shutdown__waits_for_child` — blocks until child exits

Bridge integration:
- `test_supervisor__bridge_loop_runs` — bridge thread started and alive
- `test_supervisor__bridge_loop_survives_child_restart` — child restarts, bridge still running

**`tests/unit/runner/test_file_watcher.py`**

- `test_watcher__py_change__triggers_callback`
- `test_watcher__txt_change__ignored`
- `test_watcher__debounce__batches_changes`
- `test_watcher__shutdown__stops`

**`tests/unit/runner/test_stability_guard.py`**

- `test_guard__no_crashes__stable`
- `test_guard__max_crashes_in_window__unstable`
- `test_guard__crashes_outside_window__stable`
- `test_guard__reset__clears_history`

**`tests/unit/runner/test_bridge_loop.py`**

Polling:
- `test_poll__no_commands__loops` — empty batch → loop continues
- `test_poll__single_command__dispatches_and_reports` — 1 command → handler called → result reported
- `test_poll__batch__dispatches_all` — 3 commands → all dispatched, all reported
- `test_poll__network_error__backs_off` — ConnectionError → backoff → retry
- `test_poll__410_evicted__stops_loop` — 410 → loop exits
- `test_poll__shutdown__stops_loop` — shutdown event → exits

Dispatch:
- `test_dispatch__read_commands__parallel` — 3 reads → concurrent execution
- `test_dispatch__handler_exception__reports_failed` — handler raises → failed reported
- `test_dispatch__handler_timeout__reports_failed` — too slow → timeout error

Result reporting:
- `test_report__success__calls_api` — correct args passed
- `test_report__network_error__retries` — first fails, second succeeds
- `test_report__all_retries_fail__logs_and_continues` — 3 failures → logs, loop continues
- `test_report__409__swallowed` — duplicate → no exception

Shutdown:
- `test_shutdown__waits_for_inflight` — in-flight commands complete before exit

**`tests/unit/runner/test_bridge_handlers.py`**

- `test_stub_handler__raises_not_implemented`
- `test_command_error__fields`

**`tests/unit/runner/test_file_mutation_queue.py`**

- `test_same_file__serialized` — two writes to same path → sequential
- `test_different_files__parallel` — different paths → concurrent
- `test_symlink__resolves_to_same_lock`

**`tests/unit/runner/test_heartbeat_supervised.py`**

- `test_supervised__skips_heartbeat_thread` — `OPIK_SUPERVISED=true` → `InProcessRunnerLoop` doesn't start heartbeat

### Done When

- [ ] `opik connect` stays alive as supervisor (no `execvpe`)
- [ ] Child process launched via `subprocess.Popen` with inherited terminal
- [ ] Child skips heartbeat when `OPIK_SUPERVISED=true`
- [ ] Supervisor heartbeat sends `capabilities: ["jobs", "bridge"]`
- [ ] File changes trigger child restart (debounced, extension-filtered)
- [ ] Stability guard stops restarts after 3 crashes in 30s
- [ ] Bridge poll loop runs in supervisor, survives child restarts
- [ ] Stub handlers return `not_implemented` for all command types
- [ ] Result reporting retries on network failure (3 attempts, backoff)
- [ ] File mutation queue serializes writes per realpath
- [ ] SIGTERM/SIGINT cleanly shuts down all threads and child
- [ ] Existing job polling and heartbeat behavior unchanged
- [ ] All tests pass

---

## Stage 2: File Operation Handlers

Five real handlers replacing stubs. Each implements `BridgeCommandHandler`. After this stage, bridge is fully functional end-to-end.

### Security Requirements (from Stage 1 review)

These MUST be enforced when building handlers. They are not optional.

**Path validation is mandatory at the framework level, not opt-in per handler:**
- `BridgePollLoop._execute_command` should call `validate_path()` before dispatching to the handler, so a handler cannot accidentally skip validation.
- Handlers receive a pre-validated `Path` object, not raw `args["path"]` strings.

**Symlink resolution on the write target:**
- `validate_path()` must resolve symlinks via `os.path.realpath()` and verify the resolved path starts with `os.path.realpath(repo_root)`. A symlink at `repo_root/safe.py -> /etc/cron.d/evil` must be rejected.
- Resolution must happen on every access, not cached, since symlinks can change between calls.

**Regex DoS prevention in `search_files`:**
- User-supplied regex patterns can be pathological (e.g., `(a+)+$`). Enforce a wall-clock timeout on regex search, independent of the handler timeout. Kill the search if it exceeds a per-file time limit (e.g., 5s per file).

**No raw exception strings sent to backend:**
- Already enforced in Stage 1: `_execute_command` returns generic "Internal error" for unexpected exceptions. `CommandError` messages are the only handler-controlled strings that reach the backend — keep these to structured error codes, not stack traces.

**FileMutationQueue TOCTOU:**
- The lock eviction in `FileMutationQueue` has a minor race (check `locked()` then delete). Acceptable at current scale (<1000 unique paths), but if Stage 2 handlers touch many files, consider replacing with an LRU cache with ref-counting.

### Files to Create

**`src/opik/runner/bridge_handlers/__init__.py`** — Exports all handlers.

**`src/opik/runner/bridge_handlers/path_utils.py`** — Shared validation.

```python
SENSITIVE_PATTERNS = [".env", "*.pem", "*.key", "*secret*", "*credential*"]

def validate_path(path: str, repo_root: Path) -> Path:
    # 1. Resolve relative to repo_root
    # 2. os.path.realpath() to follow symlinks
    # 3. Reject if doesn't start with repo_root
    # 4. Reject if matches sensitive patterns
    # 5. Reject if contains ".." (defense in depth)

def is_binary(path: Path) -> bool:
    # Check first 8KB for null bytes
```

**`src/opik/runner/bridge_handlers/read_file.py`** — `ReadFileHandler`

Args: `path`, `offset` (default 0), `limit` (default 2000)
Returns: `{"content", "total_lines", "truncated", "encoding"}`
Truncation: 2000 lines OR 512KB, whichever first.
Errors: `file_not_found`, `binary_file`, `path_traversal`, `sensitive_path`

**`src/opik/runner/bridge_handlers/write_file.py`** — `WriteFileHandler`

Args: `path`, `content`
Returns: `{"bytes_written", "created", "diff"}`
Creates parent dirs. Generates unified diff if file existed. Acquires `FileMutationQueue` lock.

**`src/opik/runner/bridge_handlers/edit_file.py`** — `EditFileHandler`

Args: `path`, `edits` (list of `{old_string, new_string}`)
Returns: `{"diff", "edits_applied", "fuzzy_match_used"}`

Pipeline:
1. Read file, strip BOM, detect line ending, normalize to LF
2. Match each edit against original content (exact first, fuzzy fallback)
3. Validate: unique matches, no overlaps, no no-ops
4. Apply edits in reverse offset order
5. Restore BOM + line endings
6. Write file (under mutation lock)
7. Generate unified diff with 4 lines context

Errors: `file_not_found`, `binary_file`, `match_not_found`, `match_ambiguous`, `edits_overlap`, `no_change`, `path_traversal`, `sensitive_path`

**`src/opik/runner/bridge_handlers/edit_utils.py`** — Edit sub-module:
- `strip_bom()`, `detect_line_ending()`, `normalize_to_lf()`, `restore_line_ending()`
- `fuzzy_normalize()` — NFKC, smart quotes → ASCII, em-dash → hyphen, NBSP → space, strip trailing whitespace
- `find_match()` — exact, then fuzzy fallback
- `validate_edits()` — uniqueness, no overlap, no no-change
- `apply_edits()` — reverse offset order
- `generate_diff()` — unified diff, 4 lines context

**`src/opik/runner/bridge_handlers/list_files.py`** — `ListFilesHandler`

Args: `pattern`, `path` (default "")
Returns: `{"files", "total", "truncated"}`
Uses `pathlib.Path.glob()` + `pathspec` for gitignore. Sorted by mtime (newest first). Truncation: 1000 entries OR 512KB.

**`src/opik/runner/bridge_handlers/search_files.py`** — `SearchFilesHandler`

Args: `pattern` (regex), `glob` (optional file filter), `path` (default "")
Returns: `{"matches": [{file, line, content, context_before, context_after}], "total_matches", "truncated"}`
3 lines context. Lines truncated to 500 chars. Truncation: 100 matches OR 512KB. Binary files silently skipped.

### Files to Modify

**`src/opik/runner/supervisor.py`** — Register real handlers instead of stubs:

```python
from .bridge_handlers.read_file import ReadFileHandler
from .bridge_handlers.write_file import WriteFileHandler
from .bridge_handlers.edit_file import EditFileHandler
from .bridge_handlers.list_files import ListFilesHandler
from .bridge_handlers.search_files import SearchFilesHandler

mutation_queue = FileMutationQueue()
handlers = {
    "read_file": ReadFileHandler(repo_root),
    "write_file": WriteFileHandler(repo_root, mutation_queue),
    "edit_file": EditFileHandler(repo_root, mutation_queue),
    "list_files": ListFilesHandler(repo_root),
    "search_files": SearchFilesHandler(repo_root),
}
```

**`setup.py`** — Add `pathspec` to `install_requires`.

### Implementation Order

1. `path_utils.py` + tests
2. `read_file.py` + tests
3. `write_file.py` + tests
4. `edit_utils.py` + tests
5. `edit_file.py` + tests (needs 4)
6. `list_files.py` + tests (needs `pathspec` dep)
7. `search_files.py` + tests
8. Wire real handlers into supervisor, replacing stubs

### Tests

**`tests/unit/runner/bridge_handlers/test_path_utils.py`**

- `test_validate__relative_path__resolves`
- `test_validate__dotdot_traversal__raises`
- `test_validate__symlink_escape__raises`
- `test_validate__symlink_inside_repo__ok`
- `test_validate__sensitive_env__raises`
- `test_validate__sensitive_pem__raises`
- `test_is_binary__text_file__false`
- `test_is_binary__null_bytes__true`
- `test_is_binary__empty_file__false`

**`tests/unit/runner/bridge_handlers/test_read_file.py`**

- `test_read__small_file__full_content`
- `test_read__large_file__truncates_by_lines`
- `test_read__large_file__truncates_by_bytes`
- `test_read__offset_and_limit`
- `test_read__binary__error`
- `test_read__not_found__error`
- `test_read__path_traversal__error`
- `test_read__utf8__preserved`
- `test_read__empty_file`

**`tests/unit/runner/bridge_handlers/test_write_file.py`**

- `test_write__new_file__creates`
- `test_write__new_file__creates_parent_dirs`
- `test_write__existing__overwrites_with_diff`
- `test_write__path_traversal__error`
- `test_write__sensitive_path__error`
- `test_write__bytes_written_correct`

**`tests/unit/runner/bridge_handlers/test_edit_file.py`**

Exact matching:
- `test_edit__single_exact_match`
- `test_edit__multi_edit`
- `test_edit__not_found__error`
- `test_edit__ambiguous__error`
- `test_edit__overlap__error`
- `test_edit__no_change__error`

BOM:
- `test_edit__bom_file__matches_without_bom`
- `test_edit__bom_preserved`

Line endings:
- `test_edit__crlf_file__matches_with_lf`
- `test_edit__crlf_preserved`

Fuzzy:
- `test_edit__smart_quotes__fuzzy`
- `test_edit__em_dash__fuzzy`
- `test_edit__nbsp__fuzzy`
- `test_edit__trailing_whitespace__fuzzy`
- `test_edit__fuzzy_flagged_in_result`

Multi-edit ordering:
- `test_edit__reverse_order_application`
- `test_edit__matched_against_original`

Edge cases:
- `test_edit__binary__error`
- `test_edit__file_not_found__error`
- `test_edit__empty_old_string__error`

**`tests/unit/runner/bridge_handlers/test_edit_utils.py`**

- `test_strip_bom__with_bom`
- `test_strip_bom__without_bom`
- `test_detect_line_ending__crlf`
- `test_detect_line_ending__lf`
- `test_detect_line_ending__no_newlines`
- `test_fuzzy_normalize__smart_quotes`
- `test_fuzzy_normalize__em_dash`
- `test_fuzzy_normalize__nbsp`
- `test_find_match__exact`
- `test_find_match__fuzzy_fallback`
- `test_find_match__not_found`
- `test_find_match__multiple__raises`
- `test_generate_diff__basic`
- `test_generate_diff__multiple_hunks`

**`tests/unit/runner/bridge_handlers/test_list_files.py`**

- `test_list__matches_pattern`
- `test_list__recursive_glob`
- `test_list__respects_gitignore`
- `test_list__sorted_by_mtime`
- `test_list__truncates_at_1000`
- `test_list__relative_paths`
- `test_list__scoped_to_subdir`
- `test_list__path_traversal__error`

**`tests/unit/runner/bridge_handlers/test_search_files.py`**

- `test_search__regex__matches`
- `test_search__context_lines`
- `test_search__long_line_truncated`
- `test_search__respects_gitignore`
- `test_search__glob_filter`
- `test_search__truncates_at_100`
- `test_search__binary_skipped`
- `test_search__scoped_to_subdir`
- `test_search__path_traversal__error`

### Done When

- [ ] All 5 handlers registered, replacing stubs
- [ ] Path validation blocks traversal and sensitive files for every command
- [ ] Binary detection prevents garbage output on .pyc, .pkl, images
- [ ] edit_file handles BOM, CRLF, fuzzy Unicode, multi-edit, overlap detection
- [ ] Truncation enforces dual limits (lines + bytes) on all read operations
- [ ] list_files and search_files respect .gitignore
- [ ] Unified diffs are valid and include 4 lines context
- [ ] Error codes match the contract in the design doc
- [ ] All tests pass

---

## Thread Architecture (final)

```
opik connect (Supervisor.run)
├── Main thread: signal handling, child lifecycle, restart coordination
├── Heartbeat thread: sends capabilities=["jobs", "bridge"]
├── Bridge poll thread: BridgePollLoop.run() — handles file ops
├── File watcher thread: FileWatcher.run() — triggers child restart
└── Child process: subprocess.Popen (stdout/stderr inherited)
    ├── Main thread: user's application
    ├── Runner poll thread: InProcessRunnerLoop._poll_loop()
    └── Job consumer thread: InProcessRunnerLoop._run_job_loop()
```

## New Dependencies

| Package | Stage | Purpose | Size |
|---------|-------|---------|------|
| `watchfiles` | 1 | File system watcher (Rust-based, same as uvicorn) | ~2MB wheel |
| `pathspec` | 2 | Gitignore pattern matching | ~50KB pure Python |

## Non-Goals

- No changes to existing job endpoints or behavior
- No TUI (separate plan — when added, supervisor switches to PIPE capture)
- No checklist/instrumentation scanning (Plan 04, separate)
- No frontend or OllieAssist changes
- No new REST endpoints (Fern already generated the client)
