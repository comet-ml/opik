"""Sharing behaviour of Opik clients over ref-counted connection resources.

These assert only through the public client surface (`rest_client`, `trace()`,
`flush()`, `end()`): clients with a matching connection config reuse one
transport, distinct configs get their own, and ending one client does not
disable another that shares the connection. The ref-counting/eviction mechanics
themselves are covered at the manager level in
``test_connection_resource_manager.py``.
"""

from opik.api_objects import opik_client


def _make_client(**kwargs) -> opik_client.Opik:
    return opik_client.Opik(_show_misconfiguration_message=False, **kwargs)


def test_opik_clients__matching_connection_config__share_one_rest_client():
    client_a = _make_client()
    client_b = _make_client()
    try:
        # A shared connection is observable through the public REST client:
        # both handles expose the very same underlying client object.
        assert client_a.rest_client is client_b.rest_client
    finally:
        client_a.end(flush=False)
        client_b.end(flush=False)


def test_opik_clients__distinct_connection_config__use_separate_rest_clients():
    client_a = _make_client()
    client_b = _make_client(host="http://localhost:39999/api")
    try:
        assert client_a.rest_client is not client_b.rest_client
    finally:
        client_a.end(flush=False)
        client_b.end(flush=False)


def test_opik_client__logs_after_co_located_client_ended__data_still_delivered(
    fake_backend,
):
    keeper = _make_client()
    transient = _make_client()  # shares keeper's connection

    # Ending one client releases only its reference; the shared transport must
    # stay alive for the other handle.
    transient.end(flush=False)

    keeper.trace(name="after-sibling-end")
    keeper.flush()

    assert [trace.name for trace in fake_backend.trace_trees] == ["after-sibling-end"]

    keeper.end(flush=False)
