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
