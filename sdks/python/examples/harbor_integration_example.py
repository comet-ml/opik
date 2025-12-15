"""
Harbor Integration Example

Track Harbor benchmark runs with Opik. The integration follows Opik's standard
patterns (like CrewAI) and creates hierarchical spans for trial execution:

Trace: {agent_name}/{trial_name}
├── Span: setup_environment
├── Span: setup_agent
├── Span: execute_agent
│   └── [trajectory step spans streamed in real-time]
├── Span: run_verification
│   └── Span: verify

Features:
- Automatic tracing of Trial.run and all sub-methods
- Real-time streaming of trajectory steps during agent execution
- Verifier rewards captured as feedback scores
- Token usage and cost tracking from trajectory metrics
- Automatic dataset and experiment creation for evaluation tracking

The integration automatically:
- Creates an Opik dataset for each Harbor dataset source (e.g., "terminal-bench")
- Creates an experiment named `harbor-job-{job_id[:8]}` to group all trial traces
- Links each trial's trace to the experiment as an experiment item

Prerequisites:
    pip install opik harbor
    opik configure
    Docker must be running

Usage:
    OPENAI_API_KEY=... python harbor_integration_example.py
"""

import asyncio
from datetime import datetime
from pathlib import Path

from harbor.job import Job
from harbor.models.job.config import (
    AgentConfig,
    JobConfig,
    EnvironmentConfig,
    OrchestratorConfig,
    RegistryDatasetConfig,
)
from harbor.models.registry import RemoteRegistryInfo

from opik.integrations.harbor import track_harbor


async def main():
    # Configure agent - terminus-2 creates trajectory files for detailed tracing
    # Requires OPENAI_API_KEY environment variable
    agent = AgentConfig(
        name="terminus-2",
        model_name="gpt-4o-mini",
        override_timeout_sec=30,  # 30 second timeout for demo
    )

    # Configure Terminal-Bench 2.0 dataset from Harbor registry
    # See all tasks: https://github.com/laude-institute/terminal-bench-2
    dataset = RegistryDatasetConfig(
        registry=RemoteRegistryInfo(),
        name="terminal-bench",
        version="2.0",
        task_names=["fix-git", "chess-best-move"],
    )

    # Create Harbor job with unique timestamp-based name
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    job = Job(
        JobConfig(
            job_name=f"opik-terminal-bench-{timestamp}",
            jobs_dir=Path("./harbor_jobs"),
            orchestrator=OrchestratorConfig(n_concurrent_trials=1),
            environment=EnvironmentConfig(delete=True),
            agents=[agent],
            datasets=[dataset],
        )
    )

    # Enable Opik tracking - patches Trial class methods globally
    # This follows the same pattern as track_crewai, track_openai, etc.
    tracked_job = track_harbor(
        job,
        project_name="terminal-bench-demo",
    )

    # Run benchmark - traces are created automatically
    result = await tracked_job.run()

    print(f"\nCompleted {result.stats.n_trials} trials, {result.stats.n_errors} errors")
    print("View traces at: https://www.comet.com/opik")


if __name__ == "__main__":
    asyncio.run(main())
