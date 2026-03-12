# Opik Application Context

This document provides domain knowledge about the Opik application for AI agents generating E2E tests.

## What is Opik?

Opik is an **LLM observability and evaluation platform**. It helps developers trace, evaluate, and monitor their LLM-powered applications. Think of it as "application performance monitoring (APM) for LLM apps."

## Local Development URLs

- **Frontend (UI)**: `http://localhost:5173`
- **Backend API**: `http://localhost:5173/api` (proxied through frontend)
- **Flask Test Helper Service**: `http://localhost:5555` (Python SDK bridge for tests)

## Workspace Concept

Every URL in Opik is workspace-scoped:

- **Local installs**: workspace is always `default` -> URLs are `http://localhost:5173/default/...`
- **Cloud installs**: workspace is the username -> URLs are `https://cloud.opik.com/{username}/...`

The `BasePage` class handles workspace-aware navigation automatically.

## Core Entities and URL Paths

### Projects (`/{workspace}/projects`)

Container for traces. Every trace belongs to a project.

- List/search/create/delete projects
- Click a project to see its traces

### Traces (`/{workspace}/projects/{project-id}/traces`)

A trace represents one complete execution of an LLM pipeline. Traces belong to projects.

- Each trace can have metadata, tags, feedback scores
- Traces contain spans (sub-operations)
- Traces page is accessed by clicking into a project

### Spans (within trace details)

Sub-operations within a trace (e.g., an LLM call, a retrieval step). Viewed in the trace detail sidebar.

### Threads (`/{workspace}/projects/{project-id}` -> Logs tab -> Threads toggle)

Conversation threads group related traces into multi-turn conversations. Accessed via Logs tab inside a project, then toggling to "Threads" view.

### Datasets (`/{workspace}/datasets`)

Collections of input/output pairs used for evaluation.

- List/search/create/delete datasets
- Click a dataset to manage its items

### Dataset Items (within dataset details)

Individual data records within a dataset. Managed inside the dataset detail page.

### Experiments (`/{workspace}/experiments`)

Evaluation runs that test an LLM pipeline against a dataset. Each experiment belongs to a dataset.

- List/search/delete experiments
- Click an experiment to see its items

### Experiment Items (within experiment details)

Individual evaluation results within an experiment.

### Prompts (`/{workspace}/prompts`)

Versioned prompt templates. Each prompt can have multiple commits (versions).

- List/search/create/delete prompts
- Click a prompt to see its versions, edit, create new commits

### Feedback Scores (`/{workspace}/configuration?tab=feedback-definitions`)

Definitions for scoring traces/spans. Two types:

- **Categorical**: named categories with numeric values (e.g., `{good: 1, bad: 0}`)
- **Numerical**: min/max range (e.g., 0.0 to 1.0)

### AI Provider Configuration (`/{workspace}/configuration?tab=ai-provider`)

API key management for LLM providers (OpenAI, Anthropic, etc.). Required for Playground and Online Scoring features.

### Playground (`/{workspace}/playground`)

Interactive LLM prompt testing interface. Select a model, enter a prompt, run it, see the response.

### Online Scoring Rules (within project -> Online Evaluation tab)

Automated scoring rules that evaluate new traces as they arrive. Configured per-project.

## Entity Relationships

```
Project
  ├── Traces
  │     ├── Spans
  │     └── Feedback Scores (on traces)
  └── Online Scoring Rules

Dataset
  ├── Dataset Items
  └── Experiments
        └── Experiment Items

Prompts
  └── Commits (versions)

Configuration
  ├── Feedback Definitions
  └── AI Provider Configs
```

## The Dual Interface Pattern

Every entity can be managed through **two interfaces**:

1. **UI** (browser): The Opik web frontend at `localhost:5173`
2. **Python SDK**: The `opik` Python package

Tests should verify both directions:

- Create via SDK -> verify appears in UI
- Create via UI -> verify retrievable via SDK
- Update via one -> verify reflected in the other
- Delete via one -> verify gone from the other

## Test Architecture: Flask Helper Bridge

TypeScript E2E tests cannot directly use the Python SDK. Instead, they use a **Flask HTTP service** that wraps SDK calls:

```
TypeScript Test  -->  TestHelperClient (HTTP)  -->  Flask Service (localhost:5555)  -->  Python SDK  -->  Opik Backend
```

The `TestHelperClient` class in `helpers/test-helper-client.ts` provides typed methods for all SDK operations.

## Eventual Consistency

After creating/updating entities via the SDK, there may be a short delay before they appear in the UI. The test infrastructure handles this with:

- `helperClient.waitForProjectVisible(name, retries)`
- `helperClient.waitForDatasetVisible(name, retries)`
- `helperClient.waitForTracesVisible(projectName, count, retries)`

Always use these wait methods between SDK creation and UI verification.
