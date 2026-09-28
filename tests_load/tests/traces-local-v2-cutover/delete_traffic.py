"""Delete existing traces at a steady rate through the normal SDK — the "deletes during the cutover window"
reproducer. The loop itself is shared with the spans suite (`tests_load/tests/cutover_common/delete_traffic.py`);
this file only binds it to this suite's project and resurrection mode.

This is what makes the cutover interesting: with deletion capture enabled
(ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true on the backend), each delete is recorded in the
deletion-events bridge and must be replayed onto the destination — otherwise it leaks across the swap.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python delete_traffic.py --help` for options.
"""

from _common import DEFAULT_PROJECT, build_delete_traffic_command

main = build_delete_traffic_command(default_project=DEFAULT_PROJECT, resurrect="trace")

if __name__ == "__main__":
    main()
