# Fixture Catalog

All fixtures are in `tests_end_to_end/typescript-tests/fixtures/`. Tests import `test` and `expect` from the appropriate fixture file.

## Fixture Hierarchy

```
@playwright/test (Playwright base)
    |
    v
base.fixture.ts (core fixtures)
    |
    +----> projects.fixture.ts
    +----> datasets.fixture.ts
    +----> tracing.fixture.ts
    +----> feedback-experiments-prompts.fixture.ts
```

Each feature fixture **extends** base, so all base fixtures are available in feature fixtures.

## Base Fixtures (`fixtures/base.fixture.ts`)

**Import**: `import { test, expect } from '../../fixtures/base.fixture'`

| Fixture | Type | Description |
|---------|------|-------------|
| `envConfig` | `EnvConfigManager` | Environment configuration (URLs, workspace) |
| `helperClient` | `TestHelperClient` | HTTP client for Flask test helper service |
| `projectName` | `string` | Random project name (`project_xxxxx`) |
| `datasetName` | `string` | Random dataset name (`dataset_xxxxx`) |

The `helperClient` checks Flask service health on initialization and throws if unavailable.

## Projects Fixtures (`fixtures/projects.fixture.ts`)

**Import**: `import { test, expect } from '../../fixtures/projects.fixture'`

Includes all base fixtures plus:

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `createProjectApi` | `string` | Creates a project via SDK, returns name | Yes - deletes project after test |
| `createProjectUi` | `string` | Creates a project via UI, returns name | Yes - deletes project after test |
| `projectsPage` | `ProjectsPage` | Pre-initialized ProjectsPage (navigated) | No cleanup needed |

## Datasets Fixtures (`fixtures/datasets.fixture.ts`)

**Import**: `import { test, expect } from '../../fixtures/datasets.fixture'`

Includes all base fixtures plus:

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `createDatasetSdk` | `string` | Creates a dataset via SDK, returns name | Yes - deletes dataset after test |
| `createDatasetUi` | `string` | Creates a dataset via UI, returns name | Yes - deletes dataset after test |
| `datasetsPage` | `DatasetsPage` | Pre-initialized DatasetsPage (navigated) | No cleanup needed |

## Tracing Fixtures (`fixtures/tracing.fixture.ts`)

**Import**: `import { test, expect } from '../../fixtures/tracing.fixture'`

Includes all base fixtures plus:

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `tracesPage` | `TracesPage` | Pre-initialized TracesPage | No cleanup needed |
| `threadsPage` | `ThreadsPage` | Pre-initialized ThreadsPage | No cleanup needed |
| `createTracesDecorator` | `void` | Creates 25 traces via decorator in a new project | Yes - deletes project |
| `createTracesClient` | `void` | Creates 25 traces via client in a new project | Yes - deletes project |
| `createTracesWithSpansClient` | `{traceConfig, spanConfig}` | Creates 5 traces with 2 spans each via client | Yes - deletes project |
| `createTracesWithSpansDecorator` | `{traceConfig, spanConfig}` | Creates 5 traces with 2 spans each via decorator | Yes - deletes project |
| `createTraceWithAttachmentClient` | `string` | Creates a trace with attachment, returns attachment name | Yes - deletes project |
| `createTraceWithAttachmentDecorator` | `string` | Creates a trace with attachment, returns attachment name | Yes - deletes project |
| `createTraceWithSpanAttachment` | `{attachmentName, spanName}` | Creates trace+span with attachment | Yes - deletes project |
| `createThreadsDecorator` | `ThreadConfig[]` | Creates 3 threads with 3 messages each via decorator | Yes - deletes project |
| `createThreadsClient` | `ThreadConfig[]` | Creates 3 threads with 3 messages each via client | Yes - deletes project |

## Feedback, Experiments, and Prompts Fixtures (`fixtures/feedback-experiments-prompts.fixture.ts`)

**Import**: `import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture'`

Includes all base fixtures plus:

### Feedback Scores

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `feedbackScoresPage` | `FeedbackScoresPage` | Pre-initialized FeedbackScoresPage | No cleanup needed |
| `createCategoricalFeedback` | `FeedbackDefinition` | Creates categorical feedback def `{a:1, b:2}` | Yes - deletes by ID |
| `createNumericalFeedback` | `FeedbackDefinition` | Creates numerical feedback def `[0, 1]` | Yes - deletes by ID |

### Experiments

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `experimentsPage` | `ExperimentsPage` | Pre-initialized ExperimentsPage | No cleanup needed |
| `createExperiment` | `Experiment` | Creates dataset + experiment | Yes - deletes both |
| `createExperimentWithItems` | `{experiment, datasetSize}` | Creates dataset with 10 items + experiment | Yes - deletes both |

### Prompts

| Fixture | Type | Description | Auto-cleanup |
|---------|------|-------------|--------------|
| `promptsPage` | `PromptsPage` | Pre-initialized PromptsPage | No cleanup needed |
| `promptDetailsPage` | `PromptDetailsPage` | Pre-initialized PromptDetailsPage | No cleanup needed |
| `createPrompt` | `Prompt` | Creates a prompt with default text | Yes - deletes by name |

## Fixture Usage Patterns

### Basic test with SDK resource

```typescript
import { test, expect } from '../../fixtures/projects.fixture';

test('Test name @tags', async ({ page, helperClient, createProjectApi }) => {
  // createProjectApi is the project name (string)
  // Project was created before this line runs
  // Project will be deleted after test completes (even on failure)
});
```

### Test needing only page objects (no pre-created resources)

```typescript
import { test, expect } from '../../fixtures/datasets.fixture';

test('Test name @tags', async ({ page, datasetsPage }) => {
  // datasetsPage is already navigated to the datasets page
  await datasetsPage.createDatasetByName('my-dataset');
});
```

### Test with manual cleanup (when fixture can't handle it)

```typescript
import { test, expect } from '../../fixtures/projects.fixture';

test('Test name @tags', async ({ page, helperClient, createProjectApi }) => {
  const newName = 'updated_name';
  let nameUpdated = false;

  try {
    await helperClient.updateProject(createProjectApi, newName);
    nameUpdated = true;
    // ... verification ...
  } finally {
    // Manual cleanup because fixture doesn't know about the new name
    if (nameUpdated) {
      await helperClient.deleteProject(newName);
    }
  }
});
```

### Test with tracing (project created by fixture)

```typescript
import { test, expect } from '../../fixtures/tracing.fixture';

test('Test name @tags', async ({ page, projectName, createTracesClient, tracesPage }) => {
  // At this point:
  // - A project named `projectName` exists
  // - 25 traces have been created in it
  // - tracesPage is ready to use (but needs navigation to the project first)
});
```
