"""
Test to verify that time shifts preserve time distances between traces and spans.
This ensures that when demo data is shifted to present time, all temporal relationships are maintained.
"""
import datetime
import pytest
from opik_backend.demo_data_generator import (
    calculate_time_shift_to_now,
    apply_time_shift,
)
from opik_backend.demo_data import (
    demo_traces,
    demo_spans,
    experiment_traces_grouped_by_project,
    experiment_spans_grouped_by_project,
)


class TestTimeShiftDistances:
    """Test suite for validating time shift distance preservation."""

    def test_demo_data_time_distances_preserved(self):
        """
        Verify that demo traces and demo spans maintain their time distances
        after being shifted to present time.
        """
        # Calculate time shift from traces only
        time_shift = calculate_time_shift_to_now(demo_traces)
        
        # Calculate original time distances
        demo_trace_times = [(t['start_time'], t['end_time']) for t in demo_traces]
        demo_span_times = [(s['start_time'], s['end_time']) for s in demo_spans]
        
        # Get earliest and latest times before shift
        earliest_trace = min(demo_traces, key=lambda t: t['start_time'])['start_time']
        latest_trace = max(demo_traces, key=lambda t: t['end_time'])['end_time']
        original_trace_distance = (latest_trace - earliest_trace).total_seconds()
        
        # Apply shift
        shifted_trace_times = [(apply_time_shift(s, time_shift), apply_time_shift(e, time_shift)) 
                              for s, e in demo_trace_times]
        shifted_span_times = [(apply_time_shift(s, time_shift), apply_time_shift(e, time_shift)) 
                             for s, e in demo_span_times]
        
        # Get shifted earliest and latest
        shifted_earliest = min(s for s, e in shifted_trace_times)
        shifted_latest = max(e for s, e in shifted_trace_times)
        shifted_trace_distance = (shifted_latest - shifted_earliest).total_seconds()
        
        # Verify distances are identical - this is the MAIN ASSERTION
        assert original_trace_distance == shifted_trace_distance, \
            f"Trace time distance changed: {original_trace_distance}s → {shifted_trace_distance}s"
        
        # Ensure that the time shift is positive (i.e., demo data is not from today)
        assert time_shift.days > 0, \
            f"Time shift is not positive: {time_shift.days} days (expected demo data to be in the past)"
    
    def test_experiment_data_time_distances_preserved(self):
        """
        Verify that experiment traces and spans maintain their time distances
        across all projects.
        """
        for project_id, evaluation_traces in experiment_traces_grouped_by_project.items():
            evaluation_spans = experiment_spans_grouped_by_project.get(project_id, [])
            
            if not evaluation_traces:
                continue
            
            # Calculate time shift from traces
            time_shift = calculate_time_shift_to_now(evaluation_traces)
            
            # Calculate original distances for each trace
            original_distances = {}
            for trace in evaluation_traces:
                trace_id = trace['id']
                if 'start_time' in trace and 'end_time' in trace:
                    original_distances[trace_id] = (trace['end_time'] - trace['start_time']).total_seconds()
            
            # Calculate original span-to-trace distances
            original_span_distances = {}
            for span in evaluation_spans:
                span_id = span['id']
                trace_id = span.get('trace_id')
                if trace_id and 'start_time' in span:
                    span_trace = next((t for t in evaluation_traces if t['id'] == trace_id), None)
                    if span_trace and 'start_time' in span_trace:
                        original_span_distances[span_id] = (span['start_time'] - span_trace['start_time']).total_seconds()
            
            # Apply shift to all data
            shifted_traces = {}
            for trace in evaluation_traces:
                shifted_traces[trace['id']] = {
                    'start_time': apply_time_shift(trace['start_time'], time_shift),
                    'end_time': apply_time_shift(trace['end_time'], time_shift),
                }
            
            shifted_spans = {}
            for span in evaluation_spans:
                shifted_spans[span['id']] = {
                    'start_time': apply_time_shift(span['start_time'], time_shift),
                    'end_time': apply_time_shift(span['end_time'], time_shift),
                    'trace_id': span.get('trace_id'),
                }
            
            # Verify trace durations are preserved
            for trace_id, original_duration in original_distances.items():
                if trace_id in shifted_traces:
                    shifted_start = shifted_traces[trace_id]['start_time']
                    shifted_end = shifted_traces[trace_id]['end_time']
                    shifted_duration = (shifted_end - shifted_start).total_seconds()
                    assert shifted_duration == original_duration, \
                        f"Trace {trace_id} duration changed: {original_duration}s → {shifted_duration}s"
            
            # Verify span-to-trace time offsets are preserved
            for span_id, original_offset in original_span_distances.items():
                if span_id in shifted_spans:
                    span_trace_id = shifted_spans[span_id]['trace_id']
                    if span_trace_id in shifted_traces:
                        shifted_span_start = shifted_spans[span_id]['start_time']
                        shifted_trace_start = shifted_traces[span_trace_id]['start_time']
                        shifted_offset = (shifted_span_start - shifted_trace_start).total_seconds()
                        assert shifted_offset == original_offset, \
                            f"Span {span_id} offset from trace changed: {original_offset}s → {shifted_offset}s"
    
    def test_demo_spans_all_shifted_to_latest_trace_date(self):
        """
        Verify that all demo spans end up with start_time after shift
        being close to the shifted latest trace time.
        """
        time_shift = calculate_time_shift_to_now(demo_traces)
        
        # Get latest trace end_time and shift it
        latest_trace_end = max(demo_traces, key=lambda t: t['end_time'])['end_time']
        shifted_latest_trace_end = apply_time_shift(latest_trace_end, time_shift)
        
        # All demo_spans should be from 2025-08-27 originally
        for span in demo_spans:
            shifted_span_start = apply_time_shift(span['start_time'], time_shift)
            # Spans from 2025-08-27 should shift to same date as latest trace
            assert shifted_span_start.date() == shifted_latest_trace_end.date(), \
                f"Span start_time {shifted_span_start} doesn't match shifted trace end_time {shifted_latest_trace_end}"
    
    def test_experiment_spans_maintain_earlier_dates(self):
        """
        Verify that experiment spans from earlier dates (e.g., 2025-08-20)
        maintain their time distance from the shifted reference point.
        """
        for project_id, evaluation_traces in experiment_traces_grouped_by_project.items():
            evaluation_spans = experiment_spans_grouped_by_project.get(project_id, [])
            
            if not evaluation_traces or not evaluation_spans:
                continue
            
            time_shift = calculate_time_shift_to_now(evaluation_traces)
            
            # Get the reference date (latest trace end_time)
            latest_trace_end = max(evaluation_traces, key=lambda t: t['end_time'])['end_time']
            original_latest = latest_trace_end
            shifted_latest = apply_time_shift(latest_trace_end, time_shift)
            
            # For each span, verify it maintains its distance to the latest trace
            for span in evaluation_spans:
                original_distance = (original_latest - span['start_time']).total_seconds()
                shifted_span_start = apply_time_shift(span['start_time'], time_shift)
                shifted_distance = (shifted_latest - shifted_span_start).total_seconds()
                
                assert original_distance == shifted_distance, \
                    f"Span distance to latest trace changed: {original_distance}s → {shifted_distance}s"

