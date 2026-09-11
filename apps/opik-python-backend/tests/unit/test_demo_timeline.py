"""
Tests for the demo dataset's timeline compression.

The demo's trace/span ids are minted from start_time (uuid7_from_datetime), so the id-embedded
timestamps inherit whatever spread the dataset has. Ingestion validates those timestamps against
a window around now whose configurable minimum is 12h, so the shipped dataset — which spans ~30
days — has to be compressed before it can be seeded on any deployment.

These tests pin the properties the compression has to hold: every id inside the window, span trees
still nested inside their trace, durations and ordering untouched.
"""
import collections
import datetime
import uuid

import pytest

from opik_backend.demo_data_generator import (
    DEMO_ID_MAX_AGE,
    UUID7_SUB_MS_STEP,
    separate_trace_starts,
    DemoDataContext,
    build_span_writes,
    compress_demo_timeline,
    rebase_span_tree,
    uuid7_from_datetime,
)
from opik_backend.demo_data import demo_traces, demo_spans

# The hard floor on UuidValidationConfig.window (@MinDuration(value = 12, unit = HOURS)). Staying
# inside it means the demo is accepted under every legal window configuration.
MIN_CONFIGURABLE_WINDOW = datetime.timedelta(hours=12)

NOW = datetime.datetime(2026, 3, 17, 14, 30, 0)


@pytest.fixture(scope="module")
def timeline():
    return compress_demo_timeline(demo_traces, demo_spans, now=NOW)


def embedded_timestamp(moment):
    """The instant the backend reads back out of an id minted from `moment`.

    Mirrors RetentionUtils.extractInstant: the top 48 bits of the UUID are epoch milliseconds.
    """
    minted = uuid.UUID(str(uuid7_from_datetime(moment)))
    return datetime.datetime.fromtimestamp((minted.int >> 80) / 1000.0)


class TestIngestionWindow:
    """The reason the compression exists: no id may fall outside the ingestion window."""

    @pytest.mark.parametrize("window", [
        MIN_CONFIGURABLE_WINDOW,
        datetime.timedelta(hours=24),  # UuidValidationConfig default
        datetime.timedelta(days=45),   # @MaxDuration
    ])
    def test_no_id_is_rejected_in_reject_mode(self, timeline, window):
        trace_times, span_times = timeline
        oldest_allowed, newest_allowed = NOW - window, NOW + window

        rejected = [
            (start, embedded_timestamp(start))
            for start, _ in list(trace_times.values()) + list(span_times.values())
            if not oldest_allowed <= embedded_timestamp(start) <= newest_allowed
        ]

        assert rejected == [], \
            f"{len(rejected)} ids fall outside a {window} window, e.g. {rejected[:3]}"

    def test_every_trace_and_span_is_laid_out(self, timeline):
        trace_times, span_times = timeline
        assert set(trace_times) == {item["id"] for item in demo_traces}
        assert set(span_times) == {item["id"] for item in demo_spans}

    def test_dataset_fits_the_target_window_with_margin_to_spare(self, timeline):
        trace_times, span_times = timeline
        starts = [start for start, _ in list(trace_times.values()) + list(span_times.values())]

        # A millisecond tie-break can nudge the newest trace past `now`, pulling the whole
        # timeline back a few microseconds, so allow a second of slack on the target itself.
        assert NOW - min(starts) <= DEMO_ID_MAX_AGE + datetime.timedelta(seconds=1)
        # The margin below the configuration minimum is the whole point — assert it survives.
        assert DEMO_ID_MAX_AGE < MIN_CONFIGURABLE_WINDOW

    def test_newest_trace_ends_at_now(self, timeline):
        trace_times, _ = timeline
        assert max(end for _, end in trace_times.values()) == NOW

    def test_ids_are_unique(self, timeline):
        trace_times, span_times = timeline
        minted = [
            str(uuid7_from_datetime(start))
            for start, _ in list(trace_times.values()) + list(span_times.values())
        ]
        assert len(set(minted)) == len(minted)


class TestSpanTreeAlignment:
    """Spans are rebased onto their parent trace, not shifted by the trace's delta.

    On the raw dataset 882 of 906 spans sit outside their parent trace's window — spans are bunched
    into a few hours while traces spread over 30 days. Carrying that skew through compression would
    push spans past now and trip the too_far_future check, so the rebase both enables the fix and
    corrects the misalignment.
    """

    def test_raw_dataset_really_is_misaligned(self):
        """Guard the premise above: if the dataset is ever regenerated aligned, this test tells us
        the rebase is no longer load-bearing."""
        traces_by_id = {item["id"]: item for item in demo_traces}
        misaligned = [
            span for span in demo_spans
            if not traces_by_id[span["trace_id"]]["start_time"]
            <= span["start_time"]
            <= traces_by_id[span["trace_id"]]["end_time"]
        ]
        assert misaligned, "demo spans are aligned with their traces; is the rebase still needed?"

    def test_spans_land_inside_their_parent_trace(self, timeline):
        trace_times, span_times = timeline
        # uuid7_from_datetime keeps millisecond resolution, so compare at that tolerance.
        tolerance = datetime.timedelta(milliseconds=1)

        outside = []
        for span in demo_spans:
            span_start, span_end = span_times[span["id"]]
            trace_start, trace_end = trace_times[span["trace_id"]]
            if span_start < trace_start or span_end > trace_end + tolerance:
                outside.append(span["id"])

        assert outside == [], f"{len(outside)} spans fall outside their parent trace's window"

    def test_children_stay_inside_their_parent_span(self, timeline):
        _, span_times = timeline
        tolerance = datetime.timedelta(milliseconds=1)

        for span in demo_spans:
            parent_id = span.get("parent_span_id")
            if not parent_id:
                continue
            span_start, span_end = span_times[span["id"]]
            parent_start, parent_end = span_times[parent_id]
            assert parent_start <= span_start and span_end <= parent_end + tolerance, \
                f"span {span['id']} escaped its parent {parent_id}"

    def test_rebase_moves_the_tree_as_a_rigid_body(self):
        root = {"id": "root", "start_time": datetime.datetime(2025, 1, 1, 0, 0, 0),
                "end_time": datetime.datetime(2025, 1, 1, 0, 0, 10)}
        child = {"id": "child", "parent_span_id": "root",
                 "start_time": datetime.datetime(2025, 1, 1, 0, 0, 3),
                 "end_time": datetime.datetime(2025, 1, 1, 0, 0, 7)}

        new_start = datetime.datetime(2026, 6, 1, 12, 0, 0)
        rebased = rebase_span_tree([root, child], new_start)

        assert rebased["root"] == (new_start, new_start + datetime.timedelta(seconds=10))
        # The child keeps its 3s offset and 4s duration — the tree is not rescaled.
        assert rebased["child"] == (new_start + datetime.timedelta(seconds=3),
                                    new_start + datetime.timedelta(seconds=7))

    def test_rebase_anchors_on_the_root_not_the_earliest_span(self):
        """A root span that starts before a sibling must still be the anchor."""
        root = {"id": "root", "start_time": datetime.datetime(2025, 1, 1, 0, 0, 0),
                "end_time": datetime.datetime(2025, 1, 1, 0, 0, 10)}
        child = {"id": "child", "parent_span_id": "root",
                 "start_time": datetime.datetime(2025, 1, 1, 0, 0, 5),
                 "end_time": datetime.datetime(2025, 1, 1, 0, 0, 6)}

        new_start = datetime.datetime(2026, 6, 1, 12, 0, 0)
        rebased = rebase_span_tree([child, root], new_start)

        assert rebased["root"][0] == new_start

    def test_rebase_handles_no_spans(self):
        assert rebase_span_tree([], NOW) == {}

    def test_a_span_referencing_a_missing_trace_is_reported_not_a_keyerror(self):
        """Names the dataset as the problem. A bare KeyError here is swallowed by the seeder's broad
        except and surfaces only as "demo data creation failed"."""
        start = datetime.datetime(2026, 3, 17, 10, 0, 0)
        orphan = {
            "id": "orphan-span",
            "trace_id": "trace-that-does-not-exist",
            "start_time": start,
            "end_time": start + datetime.timedelta(seconds=1),
        }
        # compress_demo_timeline lays out nothing for it, mirroring the real path.
        _, span_times = compress_demo_timeline([], [orphan], now=NOW)

        with pytest.raises(ValueError, match="not in demo_traces"):
            build_span_writes([orphan], span_times, DemoDataContext(), "proj")


class TestStructurePreserved:
    """Compression takes its reduction out of the gaps between conversations, so everything a user
    reads off a single trace or thread has to come through unchanged."""

    def test_trace_durations_are_untouched(self, timeline):
        trace_times, _ = timeline
        for item in demo_traces:
            start, end = trace_times[item["id"]]
            assert end - start == item["end_time"] - item["start_time"], \
                f"trace {item['id']} changed duration"

    def test_span_durations_are_untouched(self, timeline):
        _, span_times = timeline
        for span in demo_spans:
            start, end = span_times[span["id"]]
            assert end - start == span["end_time"] - span["start_time"], \
                f"span {span['id']} changed duration"

    def test_sorting_by_id_gives_the_same_order_as_sorting_by_start_time(self, timeline):
        """The traces list can be sorted by id, so id order has to mean chronological order.

        Ids are minted from start_time, but uuid7_from_datetime only encodes the sub-millisecond part
        to 12 bits — about 244us per step. A tie-break finer than that leaves colliding traces with
        identical timestamp bits, and their relative order in an id sort then comes from the random
        bits instead of from when they happened. 96 of the demo traces share a millisecond with
        another, so this is the majority of the list, not an edge case.
        """
        trace_times, _ = timeline
        minted = {
            key: str(uuid7_from_datetime(start))
            for key, (start, _) in trace_times.items()
        }

        by_start = sorted(trace_times, key=lambda key: trace_times[key][0])
        by_id = sorted(trace_times, key=lambda key: minted[key])

        assert by_start == by_id

    def test_the_tie_break_is_at_least_one_id_resolution_step(self):
        """Guards the step size itself: below this the nudge is invisible to the minted id."""
        assert UUID7_SUB_MS_STEP >= datetime.timedelta(microseconds=1_000_000 / 4096)

        base = datetime.datetime(2026, 3, 17, 10, 0, 0)
        first = uuid.UUID(str(uuid7_from_datetime(base)))
        nudged = uuid.UUID(str(uuid7_from_datetime(base + UUID7_SUB_MS_STEP)))

        # Compare the timestamp bits only (top 64 bits hold ms + sub-ms); the rest is random.
        assert (first.int >> 64) != (nudged.int >> 64)

    def test_trace_ordering_is_preserved(self, timeline):
        trace_times, _ = timeline
        before = [item["id"] for item in
                  sorted(demo_traces, key=lambda x: (x["start_time"], x["id"]))]
        after = [item["id"] for item in
                 sorted(demo_traces, key=lambda x: (trace_times[x["id"]][0], x["id"]))]
        assert before == after

    def test_thread_durations_are_preserved(self, timeline):
        """Thread duration in the Threads tab is derived from its traces, so intra-thread offsets
        have to survive — only the gaps *between* threads are compressed."""
        trace_times, _ = timeline
        by_thread = collections.defaultdict(list)
        for item in demo_traces:
            if item.get("thread_id"):
                by_thread[item["thread_id"]].append(item)
        assert by_thread, "demo data has no threads to check"

        for thread_id, items in by_thread.items():
            before = (max(i["end_time"] for i in items)
                      - min(i["start_time"] for i in items))
            after = (max(trace_times[i["id"]][1] for i in items)
                     - min(trace_times[i["id"]][0] for i in items))
            # Millisecond tie-breaks nudge individual traces by microseconds.
            assert abs(after - before) < datetime.timedelta(milliseconds=1), \
                f"thread {thread_id} duration drifted: {before} -> {after}"

    def test_traces_spread_across_hourly_buckets(self, timeline):
        """The over-time chart buckets by the id-embedded timestamp. Hourly granularity only reads
        as a curve if the traces actually spread over the compressed window."""
        trace_times, _ = timeline
        buckets = collections.Counter(
            int((NOW - start).total_seconds() // 3600) for start, _ in trace_times.values())
        assert len(buckets) >= 8, f"traces clumped into {len(buckets)} hourly buckets: {buckets}"


class TestRootSpanDetection:
    """A root span must survive as a root through build_span_writes.

    Testing for the presence of the `parent_span_id` key alone would also match a present-but-empty
    value, and the id remapping would mint a parent for it — making the span a child of a span that
    was never written. rebase_span_tree treats the same values as rootless, so the two have to agree
    or a trace's tree gets anchored on one span and parented on another.
    """

    @pytest.mark.parametrize("parent_value", [None, ""])
    def test_empty_parent_span_id_stays_empty(self, parent_value):
        start = datetime.datetime(2026, 3, 17, 10, 0, 0)
        span = {
            "id": "span-1",
            "trace_id": "trace-1",
            "parent_span_id": parent_value,
            "start_time": start,
            "end_time": start + datetime.timedelta(seconds=1),
        }

        writes = build_span_writes(
            [span], {"span-1": (start, start + datetime.timedelta(seconds=1))},
            DemoDataContext(), "proj")

        assert writes[0].parent_span_id == parent_value

    def test_a_real_parent_is_still_remapped(self):
        start = datetime.datetime(2026, 3, 17, 10, 0, 0)
        times = {
            "root": (start, start + datetime.timedelta(seconds=2)),
            "child": (start, start + datetime.timedelta(seconds=1)),
        }
        spans = [
            {"id": "root", "trace_id": "trace-1",
             "start_time": times["root"][0], "end_time": times["root"][1]},
            {"id": "child", "trace_id": "trace-1", "parent_span_id": "root",
             "start_time": times["child"][0], "end_time": times["child"][1]},
        ]

        context = DemoDataContext()
        writes = build_span_writes(spans, times, context, "proj")
        writes_by_id = {write.id: write for write in writes}

        # Resolve each write through the id map rather than guessing from list order.
        root_write = writes_by_id[context.uuid_map["root"]]
        child_write = writes_by_id[context.uuid_map["child"]]

        # The child points at the root's *new* id — not its own, not the dataset placeholder.
        assert child_write.parent_span_id == root_write.id
        assert child_write.parent_span_id != child_write.id
        assert child_write.parent_span_id != "root"
        # ...and the root itself stays a root.
        assert not root_write.parent_span_id

    def test_rebase_agrees_with_build_on_what_is_a_root(self):
        start = datetime.datetime(2026, 3, 17, 10, 0, 0)
        span = {
            "id": "span-1",
            "trace_id": "trace-1",
            "parent_span_id": None,
            "start_time": start,
            "end_time": start + datetime.timedelta(seconds=1),
        }

        # rebase_span_tree anchors on it, meaning it considers it the root...
        rebased = rebase_span_tree([span], NOW)
        assert rebased["span-1"][0] == NOW

        # ...and build_span_writes must not then give it a parent.
        writes = build_span_writes([span], rebased, DemoDataContext(), "proj")
        assert not writes[0].parent_span_id


class TestSeparateTraceStarts:
    """The uuid7 ordering rule on its own, without going through the compressor."""

    def test_leaves_already_separated_starts_alone(self):
        base = datetime.datetime(2026, 1, 1, 0, 0, 0)
        timings = [
            (base, "a", datetime.timedelta(seconds=1)),
            (base + datetime.timedelta(seconds=5), "b", datetime.timedelta(seconds=1)),
        ]

        assert separate_trace_starts(timings) == timings

    def test_pushes_an_identical_start_one_step_later(self):
        base = datetime.datetime(2026, 1, 1, 0, 0, 0)
        timings = [
            (base, "a", datetime.timedelta(seconds=1)),
            (base, "b", datetime.timedelta(seconds=2)),
        ]

        separated = separate_trace_starts(timings)

        assert [item[1] for item in separated] == ["a", "b"]
        assert separated[1][0] - separated[0][0] == UUID7_SUB_MS_STEP
        # Durations ride along untouched.
        assert [item[2] for item in separated] == [
            datetime.timedelta(seconds=1), datetime.timedelta(seconds=2)]

    def test_never_overtakes_a_naturally_later_trace(self):
        """The failure a per-millisecond counter allows: enough collisions to jump the next trace."""
        base = datetime.datetime(2026, 1, 1, 0, 0, 0)
        crowd = [(base, f"t{i:02d}", datetime.timedelta(seconds=1)) for i in range(10)]
        crowd.append(
            (base + datetime.timedelta(milliseconds=1), "later", datetime.timedelta(seconds=1)))

        separated = separate_trace_starts(crowd)

        assert separated[-1][1] == "later"
        starts = [item[0] for item in separated]
        assert starts == sorted(starts)
        assert all(
            later - earlier >= UUID7_SUB_MS_STEP
            for earlier, later in zip(starts, starts[1:]))

    def test_is_order_independent(self):
        base = datetime.datetime(2026, 1, 1, 0, 0, 0)
        timings = [(base, "b", datetime.timedelta(seconds=1)),
                   (base, "a", datetime.timedelta(seconds=1)),
                   (base, "c", datetime.timedelta(seconds=1))]

        assert separate_trace_starts(timings) == separate_trace_starts(list(reversed(timings)))

    def test_handles_an_empty_input(self):
        assert separate_trace_starts([]) == []


class TestCompressionEdgeCases:

    def test_empty_input(self):
        assert compress_demo_timeline([], []) == ({}, {})

    def test_dataset_already_inside_the_window_is_only_shifted(self):
        """Nothing to compress: gaps are kept as-is and the data is just moved up to now."""
        base = datetime.datetime(2025, 1, 1, 0, 0, 0)
        traces = [
            {"id": "a", "start_time": base, "end_time": base + datetime.timedelta(seconds=1)},
            {"id": "b", "start_time": base + datetime.timedelta(hours=2),
             "end_time": base + datetime.timedelta(hours=2, seconds=1)},
        ]

        trace_times, _ = compress_demo_timeline(traces, [], now=NOW)

        assert trace_times["b"][1] == NOW
        # The original 2h separation is untouched.
        assert trace_times["b"][0] - trace_times["a"][0] == datetime.timedelta(hours=2)

    def test_traces_without_a_thread_are_their_own_block(self):
        base = datetime.datetime(2025, 1, 1, 0, 0, 0)
        traces = [
            {"id": "a", "start_time": base, "end_time": base + datetime.timedelta(seconds=1)},
            {"id": "b", "start_time": base + datetime.timedelta(days=20),
             "end_time": base + datetime.timedelta(days=20, seconds=1)},
        ]

        trace_times, _ = compress_demo_timeline(traces, [], now=NOW)

        # 20 days of gap is compressed, but 'a' still precedes 'b'.
        assert trace_times["a"][0] < trace_times["b"][0]
        assert NOW - trace_times["a"][0] <= DEMO_ID_MAX_AGE + datetime.timedelta(seconds=1)

    def test_overlapping_blocks_keep_their_order(self):
        base = datetime.datetime(2025, 1, 1, 0, 0, 0)
        traces = [
            {"id": "a", "start_time": base, "end_time": base + datetime.timedelta(seconds=30)},
            # Starts before 'a' ends — a negative gap, clamped to zero.
            {"id": "b", "start_time": base + datetime.timedelta(seconds=10),
             "end_time": base + datetime.timedelta(seconds=40)},
            {"id": "c", "start_time": base + datetime.timedelta(days=10),
             "end_time": base + datetime.timedelta(days=10, seconds=5)},
        ]

        trace_times, _ = compress_demo_timeline(traces, [], now=NOW)

        assert trace_times["a"][0] <= trace_times["b"][0] <= trace_times["c"][0]

    def test_durations_exceeding_the_window_collapse_gaps_instead_of_inverting(self):
        """Degenerate input: trace time alone overruns the target. Gaps go to zero rather than
        scaling by a negative factor."""
        base = datetime.datetime(2025, 1, 1, 0, 0, 0)
        traces = [
            {"id": "a", "start_time": base, "end_time": base + datetime.timedelta(hours=8)},
            {"id": "b", "start_time": base + datetime.timedelta(days=5),
             "end_time": base + datetime.timedelta(days=5, hours=8)},
        ]

        trace_times, _ = compress_demo_timeline(traces, [], now=NOW)

        assert trace_times["a"][0] < trace_times["b"][0]
        # Gaps collapsed, so the two 8h blocks sit back to back.
        assert trace_times["b"][0] - trace_times["a"][1] == datetime.timedelta(0)

    def test_separation_survives_many_collisions_in_one_millisecond(self):
        """The separation must hold across a millisecond boundary, not just inside one.

        A per-millisecond counter cannot: with 10 traces in a bucket the tenth is nudged 9 steps
        (~2.2ms) while the counter is keyed on the original millisecond, so it overtakes a trace that
        genuinely started 1ms later. Walking in chronological order and pushing each trace one step
        past its predecessor moves that later trace forward too, so it cannot be overtaken.
        """
        base = datetime.datetime(2026, 1, 1, 0, 0, 0)
        crowded = [
            {"id": f"t{index:02d}", "start_time": base,
             "end_time": base + datetime.timedelta(seconds=1)}
            for index in range(10)
        ]
        later_start = base + datetime.timedelta(milliseconds=1)
        crowded.append({"id": "later", "start_time": later_start,
                        "end_time": later_start + datetime.timedelta(seconds=1)})

        trace_times, _ = compress_demo_timeline(crowded, [], now=NOW)

        by_start = sorted(trace_times, key=lambda key: (trace_times[key][0], key))
        by_id = sorted(
            trace_times, key=lambda key: str(uuid7_from_datetime(trace_times[key][0])))

        assert by_start == by_id
        # The naturally later trace must still be last, not overtaken by the crowd.
        assert by_id[-1] == "later"

    def test_consecutive_traces_are_at_least_one_id_step_apart(self, timeline):
        """No two traces may land closer than the id can resolve, or their id order is random."""
        trace_times, _ = timeline
        starts = sorted(start for start, _ in trace_times.values())

        closest = min(later - earlier for earlier, later in zip(starts, starts[1:]))
        assert closest >= UUID7_SUB_MS_STEP

    @pytest.mark.parametrize("bad", [
        datetime.timedelta(0),
        datetime.timedelta(seconds=-1),
    ])
    def test_rejects_a_non_positive_max_age(self, bad):
        """A zero or negative target cannot be satisfied; say so rather than emitting a layout that
        silently ignores it."""
        with pytest.raises(ValueError, match="max_age must be positive"):
            compress_demo_timeline(demo_traces, demo_spans, now=NOW, max_age=bad)

    def test_respects_a_custom_max_age(self):
        trace_times, span_times = compress_demo_timeline(
            demo_traces, demo_spans, now=NOW, max_age=datetime.timedelta(hours=4))
        starts = [start for start, _ in list(trace_times.values()) + list(span_times.values())]
        assert NOW - min(starts) <= datetime.timedelta(hours=4, seconds=1)

    def test_layout_is_deterministic(self):
        """Reordered input, not the same list twice — otherwise this only proves purity.

        40 thread blocks hold traces sharing an identical start_time, and the millisecond tie-break
        assigns its microsecond nudges in iteration order. Passing the same object could not detect
        that; reversing the input reshuffles 80 of 116 traces if the inner loop is unsorted.
        """
        first, _ = compress_demo_timeline(demo_traces, demo_spans, now=NOW)
        second, _ = compress_demo_timeline(
            list(reversed(demo_traces)), demo_spans, now=NOW)
        assert first == second

    def test_source_data_is_not_mutated(self):
        before = [(item["id"], item["start_time"], item["end_time"]) for item in demo_traces]
        before_spans = [(item["id"], item["start_time"], item["end_time"]) for item in demo_spans]

        compress_demo_timeline(demo_traces, demo_spans, now=NOW)

        assert [(i["id"], i["start_time"], i["end_time"]) for i in demo_traces] == before
        assert [(i["id"], i["start_time"], i["end_time"]) for i in demo_spans] == before_spans
