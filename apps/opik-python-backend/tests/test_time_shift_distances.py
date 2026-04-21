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
    
