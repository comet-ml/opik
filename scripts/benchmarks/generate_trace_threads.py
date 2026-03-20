#!/usr/bin/env python3
"""
Generate realistic trace_threads data for benchmarking.

Layout: 1 workspace, 10 projects, 10k threads per project, 30 traces per thread = 3M rows
Timeline: starts 2 months ago, 1 new thread per project every ~5 min
Each thread's 30 traces are spread randomly over the next hour from thread creation.

Output: CSV to stdout, pipe to clickhouse-client for loading.

Usage:
    python3 generate_trace_threads.py > trace_threads_data.csv
    cat trace_threads_data.csv | docker exec -i <container> clickhouse-client \
        --query "INSERT INTO opik.trace_threads FORMAT CSV"
"""

import uuid
import random
import sys
import time as time_mod
from datetime import datetime, timedelta, timezone

random.seed(42)  # reproducible

WORKSPACE_ID = str(uuid.uuid4())
PROJECT_IDS = [str(uuid.uuid4()) for _ in range(10)]
THREADS_PER_PROJECT = 10_000
TRACES_PER_THREAD = 30
TOTAL_ROWS = len(PROJECT_IDS) * THREADS_PER_PROJECT * TRACES_PER_THREAD  # 3M

# Timeline: start 2 months ago, 1 thread per project every ~5 min
# 10k threads / (5 min/thread) = 50,000 min ≈ 34.7 days
START_TIME = datetime.now(timezone.utc) - timedelta(days=60)
STEP_MINUTES = 5

# Threads created within the last 5 min are "active", rest are "inactive"
ACTIVE_THRESHOLD = datetime.now(timezone.utc) - timedelta(minutes=5)


def uuidv7(timestamp: datetime) -> str:
    """Generate a UUIDv7-like string from a timestamp."""
    ts_ms = int(timestamp.timestamp() * 1000)
    ts_bits = ts_ms & 0xFFFFFFFFFFFF
    rand_a = random.getrandbits(12)
    rand_b = random.getrandbits(62)
    uuid_int = (ts_bits << 80) | (0x7 << 76) | (rand_a << 64) | (0x2 << 62) | rand_b
    return str(uuid.UUID(int=uuid_int))


def format_dt9(dt: datetime) -> str:
    """Format as DateTime64(9) string."""
    return dt.strftime('%Y-%m-%d %H:%M:%S') + f'.{dt.microsecond * 1000:09d}'


def format_dt6(dt: datetime) -> str:
    """Format as DateTime64(6) string."""
    return dt.strftime('%Y-%m-%d %H:%M:%S') + f'.{dt.microsecond:06d}'


def main():
    written = 0
    start = time_mod.time()

    for step in range(THREADS_PER_PROJECT):
        thread_time = START_TIME + timedelta(minutes=step * STEP_MINUTES)
        is_active = thread_time > ACTIVE_THRESHOLD

        for project_id in PROJECT_IDS:
            thread_id = uuidv7(thread_time)

            for trace_idx in range(TRACES_PER_THREAD):
                trace_id = str(uuid.uuid4())
                trace_offset_sec = random.uniform(0, 3600)
                trace_time = thread_time + timedelta(seconds=trace_offset_sec)
                last_updated = trace_time + timedelta(seconds=random.uniform(0, 60))

                status = 'active' if is_active else 'inactive'

                # CSV: id, thread_id, project_id, workspace_id, status,
                #      created_at, last_updated_at, created_by, last_updated_by
                sys.stdout.write(
                    f"{trace_id},{thread_id},{project_id},{WORKSPACE_ID},"
                    f"{status},{format_dt9(trace_time)},{format_dt6(last_updated)},"
                    f"system,system\n"
                )
                written += 1

        if step % 500 == 0:
            elapsed = time_mod.time() - start
            pct = written / TOTAL_ROWS * 100
            print(
                f"  [{pct:.1f}%] step {step}/{THREADS_PER_PROJECT}, "
                f"{written:,} rows, {elapsed:.1f}s",
                file=sys.stderr
            )

    elapsed = time_mod.time() - start
    print(
        f"\nDone: {written:,} rows in {elapsed:.1f}s",
        file=sys.stderr
    )
    print(f"Workspace ID: {WORKSPACE_ID}", file=sys.stderr)
    print(f"Project IDs: {PROJECT_IDS}", file=sys.stderr)


if __name__ == '__main__':
    main()
