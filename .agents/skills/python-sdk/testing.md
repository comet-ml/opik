# Python SDK Testing Patterns

## Test Naming Convention

```python
# Pattern: test_WHAT__CASE_DESCRIPTION__EXPECTED_RESULT
def test_tracked_function__error_inside_inner_function__caught_in_top_level_span():
    pass

# Happy path: test_WHAT__happyflow
def test_optimization_lifecycle__happyflow():
    pass
```

## Using fake_backend (Integration Tests)

For testing integrations that create traces/spans:

```python
from tests.testlib import TraceModel, SpanModel, ANY_BUT_NONE, assert_equal
from opik.decorator import tracker

def test_track__one_nested_function__happyflow(fake_backend):
    @tracker.track
    def f_inner(x):
        return "inner-output"

    @tracker.track
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={"x": "inner-input"},
                        output={"output": "inner-output"},
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
```

## Using Verifiers (E2E Tests)

For actual API call tests:

```python
from tests.e2e import verifiers

def test_trace_creation__e2e__happyflow(opik_client: opik.Opik):
    trace = opik_client.trace(
        name="test-trace",
        input={"query": "test"},
        output={"result": "success"}
    )

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=trace.id,
        name="test-trace",
        input={"query": "test"},
        output={"result": "success"},
    )
```

## Testlib Utilities

```python
from tests.testlib import ANY_BUT_NONE, ANY_STRING, assert_equal

# ANY_BUT_NONE - matches any value that is not None
# ANY_STRING - matches any string
# assert_equal - deep comparison with ANY_* support
```

## Parameterized Tests

```python
@pytest.mark.parametrize(
    "text,expected_sentiment",
    [
        ("I love this product!", "positive"),
        ("This is terrible.", "negative"),
        ("The sky is blue.", "neutral"),
    ],
)
def test_sentiment_classification(text, expected_sentiment):
    metric = Sentiment()
    result = metric.score(text)
    assert expected_sentiment in result.reason
```

## Key Rules

- Always test **public API only**
- Use `fake_backend` for integration tests
- Use `verifiers` for E2E tests
- Study existing similar tests before adding new ones

## Running E2E Tests Locally

Pick the backend setup that matches what you're doing. Both need a few env vars the CI workflow sets implicitly.

### Option A — CI-equivalent (recommended for full-suite runs)

```bash
# backend in docker, matches GitHub Actions:
TOGGLE_RUNNERS_ENABLED=true ./opik.sh --backend

# then run the suite:
cd sdks/python
OPIK_URL_OVERRIDE=http://localhost:5173/api/ \
  venv/bin/pytest tests/e2e/ \
    --ignore=tests/e2e/test_guardrails.py \
    -vv --durations=20
```

### Option B — dev-runner (iterating on backend code)

The native Java backend inherits your shell env, so you must export MinIO credentials and the runners flag before starting it — otherwise attachment and runner tests fail on environmental grounds, not real regressions.

```bash
export AWS_ACCESS_KEY_ID=THAAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export TOGGLE_RUNNERS_ENABLED=true
./scripts/dev-runner.sh --restart

cd sdks/python
OPIK_URL_OVERRIDE=http://localhost:8080/ \
  venv/bin/pytest tests/e2e/ \
    --ignore=tests/e2e/test_guardrails.py \
    -vv --durations=20
```

The `AWS_*` values are MinIO's root-user/root-password (from [deployment/docker-compose/docker-compose.yaml](../../../deployment/docker-compose/docker-compose.yaml)). They are the canonical "EXAMPLE" strings — no real AWS account is involved; the Java backend's S3 client reuses the standard AWS env-var names when pointed at MinIO via `S3_URL`.

### Why the gotchas

- **`TOGGLE_RUNNERS_ENABLED`** defaults to `false` in docker-compose. Without it, 8 tests in `tests/e2e/runner/` error out on setup because the runners feature is disabled in the backend.
- **`--ignore=tests/e2e/test_guardrails.py`** — the guardrails Python service isn't part of the default compose stack. CI ignores these explicitly ([python_sdk_e2e_tests.yml:90](../../../.github/workflows/python_sdk_e2e_tests.yml#L90)).
- **MinIO creds** — only relevant in Option B (dev-runner). Option A's backend container already has them baked in.

### Debugging a failing e2e test

```bash
# Capture SDK DEBUG logs to a file:
OPIK_FILE_LOGGING_LEVEL=DEBUG OPIK_LOGGING_FILE=/tmp/opik-sdk.log \
  venv/bin/pytest tests/e2e/test_tracing.py::test_name -vv

# And the backend-side error is in:
docker logs opik-backend-1   # Option A
tail -f /tmp/opik-opik-backend.log   # Option B (dev-runner)
```
