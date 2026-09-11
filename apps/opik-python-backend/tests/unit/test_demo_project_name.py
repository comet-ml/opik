"""
Pins the demo project's name to the frontend's list of demo project names.

The seeder names the project; the frontend keys behaviour off that exact string — the demo banner,
and the 24h default chart range that the compressed timeline (see test_demo_timeline) needs in order
to bucket hourly instead of collapsing into a single daily bar. Nothing at runtime connects the two,
so a rename on either side is silent: seeding still succeeds and the project still renders, just
without the demo treatment. This test is the connection.

If it fails, the fix is to add the new name to DEMO_PROJECT_NAMES on the frontend, not to loosen the
assertion. That constant is a list precisely so an old name can stay in it while a new one is rolled
out to already-seeded workspaces.
"""
import json
import re
from pathlib import Path

import pytest
from pytest_httpserver import HTTPServer

from opik_backend.demo_data_generator import DEMO_PROJECT_NAME, create_demo_data
# Sibling module in the same directory; pytest puts tests/unit on sys.path, so this resolves
# whether the suite is invoked from the app root or from tests/ (as CI does).
from test_demo_data_uuid_window import decode_payload, register_demo_mocks

FRONTEND_CONSTANTS = (
    Path(__file__).resolve().parents[3] / "opik-frontend" / "src" / "constants" / "shared.ts"
)


def frontend_demo_project_names(source):
    """The string literals inside the frontend's `DEMO_PROJECT_NAMES` array.

    The array is written in terms of other constants (`[DEMO_PROJECT_NAME]`), so each entry is
    resolved against the `export const NAME = "value"` declarations in the same file.
    """
    literals = dict(re.findall(r'export const (\w+)\s*=\s*"([^"]*)"', source))

    array = re.search(
        r"export const DEMO_PROJECT_NAMES[^=]*=\s*\[(.*?)\]", source, re.DOTALL)
    assert array, "DEMO_PROJECT_NAMES array not found in the frontend constants"

    names = []
    for entry in (item.strip() for item in array.group(1).split(",")):
        if not entry:
            continue
        if entry.startswith('"'):
            names.append(entry.strip('"'))
        else:
            assert entry in literals, f"unresolved reference in DEMO_PROJECT_NAMES: {entry}"
            names.append(literals[entry])
    return names


@pytest.mark.skipif(
    not FRONTEND_CONSTANTS.exists(),
    reason="frontend checkout not present (image builds ship the python app alone)",
)
def test_frontend_recognises_the_seeded_demo_project_name():
    names = frontend_demo_project_names(FRONTEND_CONSTANTS.read_text())

    assert DEMO_PROJECT_NAME in names, (
        f"the seeder creates {DEMO_PROJECT_NAME!r} but the frontend only treats {names} as demo "
        f"projects, so the demo banner and the 24h chart default would not apply. Add it to "
        f"DEMO_PROJECT_NAMES in {FRONTEND_CONSTANTS.name}."
    )


@pytest.mark.skipif(
    not FRONTEND_CONSTANTS.exists(),
    reason="frontend checkout not present (image builds ship the python app alone)",
)
def test_the_name_list_is_not_empty_or_blank():
    """Guards the parser itself: an empty list would make the test above vacuous."""
    names = frontend_demo_project_names(FRONTEND_CONSTANTS.read_text())

    assert names, "DEMO_PROJECT_NAMES parsed as empty — the parser or the constant changed shape"
    assert all(name.strip() for name in names)


def test_seeding_sends_the_pinned_name_to_the_api():
    """What the frontend actually sees is the name on the wire, so assert that rather than the source.

    Runs the real seeding entrypoint against a mock backend and captures every project-creation
    payload plus the project_name carried on the trace batch. A source-text check could pass on dead
    code or break on a harmless refactor; this cannot.
    """
    created_names = []
    trace_project_names = []

    def capture_project(request):
        from werkzeug.wrappers import Response

        created_names.append(json.loads(request.get_data()).get("name"))
        return Response("", status=201)

    def capture_traces(request):
        from werkzeug.wrappers import Response

        payload = decode_payload(request)
        trace_project_names.extend(
            trace.get("project_name") for trace in payload.get("traces", []))
        return Response("", status=204)

    server = HTTPServer(host="localhost", port=0)
    server.start()
    try:
        # Both handlers go in through register_demo_mocks. Registering a competing expectation
        # afterwards would not work: an ordered expectation demands that the *next* request match it
        # and puts the server into permanent-failure mode otherwise, so it would depend on where
        # project creation happens to fall in the seeding flow.
        register_demo_mocks(
            server, trace_handler=capture_traces, project_handler=capture_project)
        create_demo_data(server.url_for("/"), "default", "comet_api_key")
    finally:
        server.clear()
        server.stop()

    assert created_names, "no project was created — the seeding path did not run"
    assert set(created_names) == {DEMO_PROJECT_NAME}, (
        f"seeding created projects named {set(created_names)}; the frontend only recognises "
        f"the names pinned in DEMO_PROJECT_NAMES")
    assert trace_project_names, "no traces were posted"
    assert set(trace_project_names) == {DEMO_PROJECT_NAME}
