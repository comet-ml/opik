#!/usr/bin/env python3
"""
Build a Daytona snapshot with the optimization script pre-installed.

This script creates a Daytona snapshot that includes:
- Python runtime
- Required dependencies (opik, etc.)
- The optimization script at /app/optimization_script.py
- Local opik_optimizer installed in editable mode

Usage:
    python scripts/build_optimization_snapshot.py
"""

from daytona_sdk import Daytona, Image, CreateSnapshotParams

# Path to the optimization script in the scripts folder
OPTIMIZATION_SCRIPT_PATH = "./scripts/optimization_script.py"
# Path to the local opik_optimizer directory
OPIK_OPTIMIZER_PATH = "./sdks/opik_optimizer"
SNAPSHOT_NAME = "opik-optimization-runner:0.0.6"

def build_optimization_snapshot():
    """Build and push the optimization snapshot to Daytona."""

    # Initialize Daytona client
    daytona = Daytona()

    # Build the image configuration
    image = (
        Image.debian_slim("3.11")
        # Install opik first
        .pip_install([
            "opik",
        ])
        # Copy the local opik_optimizer directory
        .add_local_dir(
            local_path=OPIK_OPTIMIZER_PATH,
            remote_path="/app/opik_optimizer"
        )
        # Install opik_optimizer in editable mode
        .run_commands("pip install -e /app/opik_optimizer")
        # Add the optimization script
        .add_local_file(
            local_path=OPTIMIZATION_SCRIPT_PATH,
            remote_path="/app/optimization_script.py"
        )
        # Set working directory
        .workdir("/app")
        # Set environment variables (will be overridden at runtime)
        .env({
            "PYTHONUNBUFFERED": "1"
        })
    )

    # Build and push the snapshot
    # The snapshot name will be used when creating sandboxes
    
    print(f"Building snapshot: {SNAPSHOT_NAME}")
    # Create the snapshot using Daytona API
    daytona.snapshot.create(
        CreateSnapshotParams(
            name=SNAPSHOT_NAME,
            image=image,
        ),
        on_logs=print,
    )

    print(f"✓ Snapshot '{SNAPSHOT_NAME}' built successfully!")
    print(f"Use this snapshot name when creating Daytona sandboxes")

if __name__ == "__main__":
    build_optimization_snapshot()
