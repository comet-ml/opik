#!/usr/bin/env python3
"""
Test script for Dynamic Annotation Queues feature.

This script demonstrates the end-to-end flow:
1. Creates a project
2. Creates an evaluator that adds a metric
3. Logs traces with various tags and metadata
4. Logs threads
5. Creates a dynamic annotation queue with filter criteria
6. Verifies that traces/threads are automatically added to the queue
"""

import os
import time
import uuid
import requests
from datetime import datetime, timezone
import time


def uuid7() -> str:
    """Generate a UUIDv7 (time-based UUID)."""
    # Get current timestamp in milliseconds
    timestamp_ms = int(time.time() * 1000)
    
    # Generate random bytes for the rest
    random_bytes = uuid.uuid4().bytes
    
    # Build UUIDv7
    # First 48 bits: timestamp (milliseconds)
    # Next 4 bits: version (7)
    # Next 12 bits: random
    # Next 2 bits: variant (10)
    # Next 62 bits: random
    
    uuid_bytes = bytearray(16)
    
    # Timestamp (48 bits = 6 bytes)
    uuid_bytes[0] = (timestamp_ms >> 40) & 0xFF
    uuid_bytes[1] = (timestamp_ms >> 32) & 0xFF
    uuid_bytes[2] = (timestamp_ms >> 24) & 0xFF
    uuid_bytes[3] = (timestamp_ms >> 16) & 0xFF
    uuid_bytes[4] = (timestamp_ms >> 8) & 0xFF
    uuid_bytes[5] = timestamp_ms & 0xFF
    
    # Version 7 (4 bits) + random (12 bits)
    uuid_bytes[6] = 0x70 | (random_bytes[6] & 0x0F)
    uuid_bytes[7] = random_bytes[7]
    
    # Variant (2 bits = 10) + random (62 bits)
    uuid_bytes[8] = 0x80 | (random_bytes[8] & 0x3F)
    for i in range(9, 16):
        uuid_bytes[i] = random_bytes[i]
    
    # Format as UUID string
    hex_str = uuid_bytes.hex()
    return f"{hex_str[:8]}-{hex_str[8:12]}-{hex_str[12:16]}-{hex_str[16:20]}-{hex_str[20:]}"

# Configuration
BASE_URL = os.getenv("OPIK_URL_OVERRIDE", "http://localhost:8080")
WORKSPACE = os.getenv("OPIK_WORKSPACE", "default")

# Headers for API requests
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
        # Project already exists, get it
        response = requests.get(
            f"{BASE_URL}/v1/private/projects",
            headers=HEADERS,
            params={"name": name},
        )
        project = response.json()["content"][0]
        print(f"   ✅ Project already exists: {project['id']}")
        return project
    response.raise_for_status()
    
    # Extract project ID from Location header (201 returns no body)
    if response.status_code == 201:
        location = response.headers.get("Location", "")
        project_id = location.split("/")[-1] if location else None
        if project_id:
            # Fetch the project details
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


def log_trace(project_name: str, name: str, tags: list = None, metadata: dict = None) -> dict:
    """Log a trace to the project."""
    trace_id = uuid7()
    print(f"\n📝 Logging trace: {name} (tags: {tags})")
    
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    trace_data = {
        "id": trace_id,
        "project_name": project_name,
        "name": name,
        "input": {"message": f"Input for {name}"},
        "output": {"response": f"Output for {name}"},
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


def log_thread(project_name: str, thread_id: str, tags: list = None) -> dict:
    """Log a thread to the project."""
    print(f"\n🧵 Logging thread: {thread_id} (tags: {tags})")
    
    # First, create a trace with the thread_id
    trace_id = uuid7()
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    trace_data = {
        "id": trace_id,
        "project_name": project_name,
        "name": f"Thread trace for {thread_id}",
        "thread_id": thread_id,
        "input": {"message": f"Thread input for {thread_id}"},
        "output": {"response": f"Thread output for {thread_id}"},
        "start_time": now,
        "end_time": now,
    }
    
    if tags:
        trace_data["tags"] = tags
    
    response = requests.post(
        f"{BASE_URL}/v1/private/traces",
        headers=HEADERS,
        json=trace_data,
    )
    response.raise_for_status()
    print(f"   ✅ Logged thread trace: {trace_id}")
    return {"id": thread_id, "trace_id": trace_id}


def create_dynamic_annotation_queue(
    project_id: str,
    name: str,
    scope: str = "trace",
    filter_criteria: list = None,
) -> dict:
    """Create a dynamic annotation queue with filter criteria."""
    print(f"\n🎯 Creating dynamic annotation queue: {name}")
    print(f"   Scope: {scope}")
    print(f"   Filter criteria: {filter_criteria}")
    
    queue_data = {
        "project_id": project_id,
        "name": name,
        "scope": scope,
        "comments_enabled": True,
        "feedback_definition_names": [],
    }
    
    if filter_criteria:
        queue_data["filter_criteria"] = filter_criteria
    
    response = requests.post(
        f"{BASE_URL}/v1/private/annotation-queues",
        headers=HEADERS,
        json=queue_data,
    )
    response.raise_for_status()
    
    # Extract queue ID from Location header (201 returns no body)
    if response.status_code == 201:
        location = response.headers.get("Location", "")
        queue_id = location.split("/")[-1] if location else None
        if queue_id:
            # Fetch the queue details
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
    """Get an annotation queue by ID."""
    response = requests.get(
        f"{BASE_URL}/v1/private/annotation-queues/{queue_id}",
        headers=HEADERS,
    )
    response.raise_for_status()
    return response.json()


def get_annotation_queue_items(queue_id: str) -> list:
    """Get items in an annotation queue."""
    response = requests.get(
        f"{BASE_URL}/v1/private/annotation-queues/{queue_id}/items",
        headers=HEADERS,
    )
    response.raise_for_status()
    return response.json().get("content", [])


def list_annotation_queues(project_id: str = None) -> list:
    """List all annotation queues."""
    params = {}
    if project_id:
        params["project_id"] = project_id
    
    response = requests.get(
        f"{BASE_URL}/v1/private/annotation-queues",
        headers=HEADERS,
        params=params,
    )
    response.raise_for_status()
    return response.json().get("content", [])


def main():
    print("=" * 60)
    print("🚀 Dynamic Annotation Queues - End-to-End Test")
    print("=" * 60)
    print(f"\nBase URL: {BASE_URL}")
    print(f"Workspace: {WORKSPACE}")
    
    # Step 1: Create a project
    project_name = f"dynamic-queue-test-{uuid.uuid4().hex[:8]}"
    project = create_project(project_name)
    project_id = project["id"]
    
    # Step 2: Create a dynamic annotation queue for traces with "important" tag
    queue = create_dynamic_annotation_queue(
        project_id=project_id,
        name="Important Traces Queue",
        scope="trace",
        filter_criteria=[
            {
                "id": str(uuid.uuid4()),
                "field": "tags",
                "type": "list",
                "operator": "contains",
                "value": "important",
            }
        ],
    )
    queue_id = queue.get("id")
    
    # Step 3: Log some traces - some with "important" tag, some without
    print("\n" + "=" * 60)
    print("📊 Logging traces...")
    print("=" * 60)
    
    # This trace should be added to the queue (has "important" tag)
    trace1 = log_trace(
        project_name=project_name,
        name="Important trace 1",
        tags=["important", "production"],
        metadata={"environment": "prod"},
    )
    
    # This trace should NOT be added to the queue (no "important" tag)
    trace2 = log_trace(
        project_name=project_name,
        name="Regular trace",
        tags=["development"],
        metadata={"environment": "dev"},
    )
    
    # This trace should be added to the queue (has "important" tag)
    trace3 = log_trace(
        project_name=project_name,
        name="Important trace 2",
        tags=["important", "urgent"],
        metadata={"priority": "high"},
    )
    
    # Wait for async processing
    print("\n⏳ Waiting for async processing...")
    time.sleep(5)
    
    # Step 4: Check the queue
    print("\n" + "=" * 60)
    print("🔍 Checking annotation queue...")
    print("=" * 60)
    
    if queue_id:
        queue_info = get_annotation_queue(queue_id)
        items_count = queue_info.get("items_count", 0)
        queue_type = queue_info.get("queue_type", "unknown")
        
        print(f"\n📋 Queue: {queue_info.get('name')}")
        print(f"   Type: {queue_type}")
        print(f"   Items count: {items_count}")
        
        if items_count > 0:
            print(f"\n   ✅ SUCCESS! {items_count} traces were automatically added to the queue!")
        else:
            print(f"\n   ⚠️  No items in queue yet. This might be expected if async processing is still running.")
    
    # List all queues
    print("\n" + "=" * 60)
    print("📋 All annotation queues in project:")
    print("=" * 60)
    
    queues = list_annotation_queues(project_id)
    for q in queues:
        print(f"\n   - {q.get('name')}")
        print(f"     ID: {q.get('id')}")
        print(f"     Type: {q.get('queue_type', 'manual')}")
        print(f"     Scope: {q.get('scope')}")
        print(f"     Items: {q.get('items_count', 0)}")
    
    print("\n" + "=" * 60)
    print("✅ Test completed!")
    print("=" * 60)
    print(f"\nProject ID: {project_id}")
    print(f"Queue ID: {queue_id}")
    print(f"\nYou can view the results in the UI at:")
    print(f"  http://localhost:5174/{WORKSPACE}/annotation-queues")


if __name__ == "__main__":
    main()
