"""Delete existing TRACES at a steady rate through the normal SDK — the "span deletes during the cutover window"
reproducer. The loop itself is shared with the traces suite (`tests_load/tests/cutover_common/delete_traffic.py`);
this file only binds it to this suite's project and resurrection mode.

**It deletes traces, not spans, and that is the whole point.** Spans have no standalone delete endpoint: every span
delete is the cascade of a trace delete, through `SpanService.deleteByTraceIds`. With span deletion capture enabled
(`ANALYTICS_DB_DATA_MODEL_SPAN_DELETION_EVENTS_CAPTURE_ENABLED=true` on the backend), that cascade records each removed
span id in the deletion-events bridge with `source_table = 'spans'` and reason `CASCADE`, and the cutover must replay
those keys onto the destination — otherwise they leak across the swap.

Two consequences worth holding on to while reading the counts this prints:

  * one delete call removes MANY spans. A trace carrying eight spans bridges eight rows, so the bridged volume per
    user action is larger and burstier than the traces cutover's was. That is the production shape.
  * the span ids being deleted are never named here. It pulls traces and deletes them; which spans that reaches is the
    backend's business. So the "did the replay work" question is answered by the runbook's gates (verify.sh pre-swap,
    reconcile.sh's four counts post-swap), not by anything this script prints.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python delete_traffic.py --help` for options.
"""

from _common import DEFAULT_PROJECT, build_delete_traffic_command

main = build_delete_traffic_command(default_project=DEFAULT_PROJECT, resurrect="spans")

if __name__ == "__main__":
    main()
