#!/usr/bin/env python3
"""
Comprehensive End-to-End Test for Dynamic Annotation Queues

This test demonstrates the full flow of dynamic annotation queues:
1. Create a project
2. Create multiple dynamic annotation queues with different filter criteria
3. Log traces with various attributes (tags, metadata)
4. Verify that traces are automatically added to the correct queues based on criteria
5. Add feedback scores to traces (simulating evaluator behavior)
6. Create a queue that filters by feedback scores (for traces created after queue)

Note: Dynamic queues evaluate traces at creation time. Feedback score filtering
works for traces created AFTER the queue is created with feedback score criteria.
"""

import os
import uuid
import time
import requests
from datetime import datetime, timezone


def uuid7() -> str:
    """Generate a UUIDv7 (time-based UUID)."""
    timestamp_ms = int(time.time() * 1000)
    random_bytes = uuid.uuid4().bytes
    
    uuid_bytes = bytearray(16)
    uuid_bytes[0] = (timestamp_ms >> 40) & 0xFF
    uuid_bytes[1] = (timestamp_ms >> 32) & 0xFF
    uuid_bytes[2] = (timestamp_ms >> 24) & 0xFF
    uuid_bytes[3] = (timestamp_ms >> 16) & 0xFF
    uuid_bytes[4] = (timestamp_ms >> 8) & 0xFF
    uuid_bytes[5] = timestamp_ms & 0xFF
    uuid_bytes[6] = 0x70 | (random_bytes[6] & 0x0F)
    uuid_bytes[7] = random_bytes[7]
    uuid_bytes[8] = 0x80 | (random_bytes[8] & 0x3F)
    for i in range(9, 16):
        uuid_bytes[i] = random_bytes[i]
    
    hex_str = uuid_bytes.hex()
    return f"{hex_str[:8]}-{hex_str[8:12]}-{hex_str[12:16]}-{hex_str[16:20]}-{hex_str[20:]}"


# Configuration
BASE_URL = os.getenv("OPIK_URL_OVERRIDE", "http://localhost:8080")
WORKSPACE = os.getenv("OPIK_WORKSPACE", "default")

HEADERS = {
    "Content-Type": "application/json",
    "Comet-Workspace": WORKSPACE,
}


def create_project(name: str) -> dict:
    """Create a new project."""
    print(f"\n📁 Creating project: {name}")
    response = requests.post(
        f"{BASE_URL}/v1/private/projects",
        headers=HEADERS,
        json={"name": name},
    )
    if response.status_code == 409:
        response = requests.get(
            f"{BASE_URL}/v1/private/projects",
            headers=HEADERS,
            params={"name": name},
        )
        response.raise_for_status()
        projects = response.json().get("content", [])
        if projects:
            print(f"   ✅ Using existing project: {projects[0]['id']}")
            return projects[0]
    
    response.raise_for_status()
    
    if response.status_code == 201:
        location = response.headers.get("Location", "")
        project_id = location.split("/")[-1] if location else None
        if project_id:
            response = requests.get(
                f"{BASE_URL}/v1/private/projects/{project_id}",
                headers=HEADERS,
            )
            response.raise_for_status()
            project = response.json()
            print(f"   ✅ Created project: {project['id']}")
            return project
    
    project = response.json()
    print(f"   ✅ Created project: {project['id']}")
    return project


def log_trace(project_name: str, name: str, tags: list = None, metadata: dict = None,
              input_data: dict = None, output_data: dict = None) -> dict:
    """Log a trace to the project."""
    trace_id = uuid7()
    print(f"\n📝 Logging trace: {name}")
    if tags:
        print(f"   Tags: {tags}")
    if metadata:
        print(f"   Metadata: {metadata}")
    
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    trace_data = {
        "id": trace_id,
        "project_name": project_name,
        "name": name,
        "input": input_data or {"message": f"Input for {name}"},
        "output": output_data or {"response": f"Output for {name}"},
        "start_time": now,
        "end_time": now,
    }
    
    if tags:
        trace_data["tags"] = tags
    if metadata:
        trace_data["metadata"] = metadata
    
    response = requests.post(
        f"{BASE_URL}/v1/private/traces",
        headers=HEADERS,
        json=trace_data,
    )
    response.raise_for_status()
    print(f"   ✅ Logged trace: {trace_id}")
    return {"id": trace_id, **trace_data}


def add_feedback_score(trace_id: str, project_id: str, score_name: str, value: float,
                       category_name: str = None, reason: str = None) -> None:
    """Add a feedback score to a trace."""
    print(f"\n⭐ Adding feedback score to trace {trace_id[:8]}...")
    print(f"   Score: {score_name} = {value}")
    
    score_data = {
        "id": str(uuid.uuid4()),
        "name": score_name,
        "value": value,
        "source": "sdk",
        "project_id": project_id,
    }
    
    if category_name:
        score_data["category_name"] = category_name
    if reason:
        score_data["reason"] = reason
    
    response = requests.put(
        f"{BASE_URL}/v1/private/traces/{trace_id}/feedback-scores",
        headers=HEADERS,
        json=score_data,
    )
    response.raise_for_status()
    print(f"   ✅ Added feedback score")


def create_dynamic_annotation_queue(project_id: str, name: str, scope: str,
                                    filter_criteria: list, description: str = None) -> dict:
    """Create a dynamic annotation queue with filter criteria."""
    print(f"\n🎯 Creating dynamic annotation queue: {name}")
    print(f"   Scope: {scope}")
    print(f"   Filter criteria: {filter_criteria}")
    
    queue_data = {
        "project_id": project_id,
        "name": name,
        "scope": scope,
        "filter_criteria": filter_criteria,
    }
    
    if description:
        queue_data["description"] = description
    
    response = requests.post(
        f"{BASE_URL}/v1/private/annotation-queues",
        headers=HEADERS,
        json=queue_data,
    )
    response.raise_for_status()
    
    if response.status_code == 201:
        location = response.headers.get("Location", "")
        queue_id = location.split("/")[-1] if location else None
        if queue_id:
            response = requests.get(
                f"{BASE_URL}/v1/private/annotation-queues/{queue_id}",
                headers=HEADERS,
            )
            response.raise_for_status()
            queue = response.json()
            print(f"   ✅ Created queue: {queue.get('id', 'unknown')}")
            return queue
    
    queue = response.json()
    print(f"   ✅ Created queue: {queue.get('id', 'unknown')}")
    return queue


def get_annotation_queue(queue_id: str) -> dict:
    """Get annotation queue details."""
    response = requests.get(
        f"{BASE_URL}/v1/private/annotation-queues/{queue_id}",
        headers=HEADERS,
    )
    response.raise_for_status()
    return response.json()


def get_queue_items(queue_id: str) -> list:
    """Get items in an annotation queue."""
    response = requests.get(
        f"{BASE_URL}/v1/private/annotation-queues/{queue_id}/items",
        headers=HEADERS,
    )
    response.raise_for_status()
    return response.json().get("content", [])


def print_separator(title: str = None):
    """Print a visual separator."""
    print("\n" + "=" * 70)
    if title:
        print(f"  {title}")
        print("=" * 70)


def main():
    print_separator("🚀 Dynamic Annotation Queues - Comprehensive E2E Test")
    print(f"\nBase URL: {BASE_URL}")
    print(f"Workspace: {WORKSPACE}")
    
    # Step 1: Create a project
    project_name = f"dynamic-queue-comprehensive-{uuid.uuid4().hex[:8]}"
    project = create_project(project_name)
    project_id = project["id"]
    
    print_separator("Step 1: Creating Dynamic Annotation Queues")
    
    # Queue 1: Filter by "production" tag
    queue_production = create_dynamic_annotation_queue(
        project_id=project_id,
        name="Production Traces",
        scope="trace",
        description="Traces from production environment",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "tags",
                "type": "list",
                "operator": "contains",
                "value": "production",
            }
        ],
    )
    
    # Queue 2: Filter by "error" tag
    queue_errors = create_dynamic_annotation_queue(
        project_id=project_id,
        name="Error Traces",
        scope="trace",
        description="Traces with errors",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "tags",
                "type": "list",
                "operator": "contains",
                "value": "error",
            }
        ],
    )
    
    # Queue 3: Filter by multiple criteria (AND logic) - production AND high-priority
    queue_critical = create_dynamic_annotation_queue(
        project_id=project_id,
        name="Critical Production Traces",
        scope="trace",
        description="High-priority production traces",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "tags",
                "type": "list",
                "operator": "contains",
                "value": "production",
            },
            {
                "id": str(uuid.uuid4()),
                "field": "tags",
                "type": "list",
                "operator": "contains",
                "value": "high-priority",
            },
        ],
    )
    
    # Queue 4: Filter by name containing "important"
    queue_important = create_dynamic_annotation_queue(
        project_id=project_id,
        name="Important Traces",
        scope="trace",
        description="Traces with 'important' in name",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "name",
                "type": "string",
                "operator": "contains",
                "value": "important",
            }
        ],
    )
    
    print_separator("Step 2: Logging Traces with Various Attributes")
    
    # Log various traces
    traces = []
    
    # Trace 1: Production trace (should go to: Production, Critical if also high-priority)
    traces.append(log_trace(
        project_name=project_name,
        name="User login request",
        tags=["production", "auth"],
        metadata={"environment": "prod", "region": "us-east-1"},
    ))
    
    # Trace 2: Production + high-priority (should go to: Production, Critical)
    traces.append(log_trace(
        project_name=project_name,
        name="Payment processing",
        tags=["production", "high-priority", "payment"],
        metadata={"environment": "prod", "amount": 99.99},
    ))
    
    # Trace 3: Error trace (should go to: Error)
    traces.append(log_trace(
        project_name=project_name,
        name="Database connection failed",
        tags=["error", "database"],
        metadata={"error_code": "DB_CONN_001"},
    ))
    
    # Trace 4: Production + error (should go to: Production, Error)
    traces.append(log_trace(
        project_name=project_name,
        name="API timeout in production",
        tags=["production", "error", "api"],
        metadata={"environment": "prod", "timeout_ms": 5000},
    ))
    
    # Trace 5: Development trace (should not go to any queue)
    traces.append(log_trace(
        project_name=project_name,
        name="Local development test",
        tags=["development", "test"],
        metadata={"environment": "dev"},
    ))
    
    # Trace 6: Important trace (should go to: Important)
    traces.append(log_trace(
        project_name=project_name,
        name="This is an important business event",
        tags=["business"],
        metadata={"importance": "high"},
    ))
    
    # Trace 7: Production + high-priority + error (should go to: Production, Error, Critical)
    traces.append(log_trace(
        project_name=project_name,
        name="Critical production error",
        tags=["production", "high-priority", "error", "critical"],
        metadata={"environment": "prod", "severity": "critical"},
    ))
    
    # Wait for async processing
    print("\n⏳ Waiting for async processing...")
    time.sleep(5)
    
    print_separator("Step 3: Verifying Queue Contents")
    
    # Check each queue
    results = {}
    
    for queue_name, queue in [
        ("Production Traces", queue_production),
        ("Error Traces", queue_errors),
        ("Critical Production Traces", queue_critical),
        ("Important Traces", queue_important),
    ]:
        queue_info = get_annotation_queue(queue["id"])
        items_count = queue_info.get("items_count", 0)
        results[queue_name] = items_count
        
        print(f"\n📋 {queue_name}")
        print(f"   Queue ID: {queue['id']}")
        print(f"   Items count: {items_count}")
    
    print_separator("Step 4: Results Summary")
    
    # Expected results:
    # - Production Traces: 4 (traces 1, 2, 4, 7)
    # - Error Traces: 3 (traces 3, 4, 7)
    # - Critical Production Traces: 2 (traces 2, 7 - both production AND high-priority)
    # - Important Traces: 1 (trace 6 - name contains "important")
    
    expected = {
        "Production Traces": 4,
        "Error Traces": 3,
        "Critical Production Traces": 2,
        "Important Traces": 1,
    }
    
    all_passed = True
    for queue_name, expected_count in expected.items():
        actual_count = results.get(queue_name, 0)
        status = "✅" if actual_count == expected_count else "❌"
        if actual_count != expected_count:
            all_passed = False
        print(f"{status} {queue_name}: {actual_count} items (expected: {expected_count})")
    
    print_separator("Step 5: Adding Feedback Scores (Simulating Evaluator)")
    
    # Add feedback scores to some traces
    # Note: These won't affect existing queues, but demonstrate the feedback score API
    
    # Add "quality_score" to production traces
    for i, trace in enumerate(traces[:2]):  # First two traces
        add_feedback_score(
            trace_id=trace["id"],
            project_id=project_id,
            score_name="quality_score",
            value=0.8 + (i * 0.1),
            reason="Automated quality assessment",
        )
    
    # Add "error_severity" to error traces
    add_feedback_score(
        trace_id=traces[2]["id"],  # Database connection failed
        project_id=project_id,
        score_name="error_severity",
        value=0.7,
        category_name="high",
        reason="Database errors are high severity",
    )
    
    print_separator("Step 6: Creating Queue with Feedback Score Filter")
    
    # Create a new queue that filters by feedback score
    # This will only capture NEW traces created after this queue
    queue_high_quality = create_dynamic_annotation_queue(
        project_id=project_id,
        name="High Quality Traces",
        scope="trace",
        description="Traces with quality_score > 0.8",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "feedback_scores",
                "key": "quality_score",
                "type": "feedback_scores_number",
                "operator": "greater_than",
                "value": "0.8",
            }
        ],
    )
    
    # Log a new trace with high quality (simulating a trace that would be scored immediately)
    # Note: In real scenario, the evaluator would add the score, but for testing we log first
    print("\n📝 Logging new trace for feedback score queue test...")
    new_trace = log_trace(
        project_name=project_name,
        name="High quality API response",
        tags=["production", "api"],
        metadata={"quality": "excellent"},
    )
    
    # In a real scenario with online scoring, the evaluator would add the score
    # and the dynamic queue would evaluate based on that score
    
    print_separator("Test Complete!")
    
    print(f"\nProject ID: {project_id}")
    print(f"Project Name: {project_name}")
    print(f"\nQueues created:")
    print(f"  - Production Traces: {queue_production['id']}")
    print(f"  - Error Traces: {queue_errors['id']}")
    print(f"  - Critical Production Traces: {queue_critical['id']}")
    print(f"  - Important Traces: {queue_important['id']}")
    print(f"  - High Quality Traces: {queue_high_quality['id']}")
    
    print(f"\nView results in UI at:")
    print(f"  http://localhost:5174/{WORKSPACE}/annotation-queues")
    
    if all_passed:
        print("\n✅ All tests passed!")
        return 0
    else:
        print("\n❌ Some tests failed!")
        return 1


if __name__ == "__main__":
    exit(main())
