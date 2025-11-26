# Opik Dashboard Widgets - Implementation Plan

**Document Version:** 1.0  
**Date:** November 21, 2025  
**Status:** Phase 1 - Ready for Implementation

> **📋 Quick Review:** For a concise, non-technical summary, see [dashboard-mvp-summary.md](./dashboard-mvp-summary.md) (5 min read)  
> **📖 This Document:** Full technical implementation plan with API specs, code examples, and detailed architecture (20 min read)

---

## Executive Summary

**This is a technical implementation reference document.** For high-level overview, timeline, and scope, see [`dashboard-mvp-summary.md`](./dashboard-mvp-summary.md).

**What's in this document:**

- API specifications (request/response formats)
- TypeScript interfaces and component references
- Code examples and implementation patterns
- Database schemas and data structures
- Technical architecture decisions

**What's NOT in this document:**

- Project timelines and estimates (see summary)
- Widget descriptions and use cases (see summary)
- Success metrics and goals (see summary)
- Team planning and resources (see summary)

---

## Table of Contents

0. [Current State & Required Work](#0-current-state--required-work)
1. [General Dashboard Features](#1-general-dashboard-features)
2. [Stats Widgets](#2-stats-widgets)
3. [Text Widgets](#3-text-widgets)
4. [Project Metric Charts](#4-project-metric-charts)
5. [Experiment Widgets](#5-experiment-widgets)
6. [Cost Widgets](#6-cost-widgets)
7. [Backend API Reference](#7-backend-api-reference)
8. [Phase 2 Preview](#8-phase-2-preview)
9. [Appendix B: Technology Stack](#appendix-b-technology-stack)
10. [Appendix C: Dashboard Architecture](#appendix-c-dashboard-architecture)

---

## 0. Current State & Required Work

### 0.1 Existing Implementation (PoC)

**What Currently Exists:**

- Basic dashboard page at `/dashboards` route
- localStorage-based state management (`useDashboardState` hook)
- Simple widget rendering (currently only metric charts)
- React Grid Layout integration (basic setup)
- Add section/widget buttons

**Current Files:**

```
apps/opik-frontend/src/
├── components/pages/DashboardPage/
│   ├── DashboardPage.tsx              (main page)
│   ├── DashboardSection.tsx           (section container)
│   ├── DashboardWidget.tsx            (widget renderer)
│   ├── DashboardWidgetGrid.tsx        (grid layout)
│   └── AddWidgetDialog.tsx            (widget selector)
├── hooks/
│   └── useDashboardState.ts           (localStorage state)
└── types/
    └── dashboard.ts                   (type definitions)
```

**Issues with Current Implementation:**

- ⚠️ **AI-generated code** - not reviewed or tested
- ⚠️ **localStorage only** - no backend integration
- ⚠️ **No CRUD UI** - can't create/edit/delete dashboards
- ⚠️ **Single dashboard** - hardcoded, no multi-dashboard support
- ⚠️ **Limited widgets** - only metric charts work
- ⚠️ **No error handling** - crashes on widget errors
- ⚠️ **No loading states** - poor UX during data fetch
- ⚠️ **No validation** - invalid configs can break UI

---

### 0.2 What Needs to be Built

#### Phase 0: Foundation (Week 1 - 4 days)

**Dashboard List Page:**

```typescript
// NEW: apps/opik-frontend/src/components/pages/DashboardsPage/
-DashboardsPage.tsx - // Simple table view (like Projects/Traces)
  CreateDashboardDialog.tsx - // Create new dashboard
  CloneDashboardDialog.tsx - // Clone existing dashboard
  DeleteDashboardDialog.tsx; // Delete confirmation
```

**Features:**

- **Simple table view** (matches existing Projects/Traces/Experiments pattern)
- Columns: Name, Description, Last Modified, Actions
- Create new dashboard (name + description only)
- Clone existing dashboard (copies entire config + sections + widgets)
- Delete dashboard with confirmation
- Search by name
- Sort by name/last modified
- Click row to open dashboard

**No Built-in/Template Dashboards for MVP:**

- Users start with empty list
- Must create dashboards manually
- Dashboard templates deferred to Phase 2

**API Integration:**

```typescript
// NEW: apps/opik-frontend/src/api/dashboards/
-useDashboardsList.ts - // GET /dashboards
  useCreateDashboard.ts - // POST /dashboards
  useCloneDashboard.ts - // POST /dashboards (with cloned config)
  useUpdateDashboard.ts - // PATCH /dashboards/{id}
  useDeleteDashboard.ts - // DELETE /dashboards/{id}
  useDashboard.ts; // GET /dashboards/{id}
```

**Clone Dashboard Logic:**

```typescript
// Clone reuses existing CREATE endpoint with modified config
async function cloneDashboard(sourceDashboardId: string, newName: string) {
  // 1. Fetch source dashboard
  const source = await getDashboard(sourceDashboardId);

  // 2. Create new dashboard with same config
  const cloned = await createDashboard({
    name: newName,
    description: `Cloned from ${source.name}`,
    config: source.config, // ✅ Copy entire config (sections, widgets, filters)
  });

  return cloned;
}
```

**Effort:** 3 days (list page with CRUD + clone, dialogs, API hooks)

---

**Dashboard Detail Page Refactor:**

```typescript
// REFACTOR: apps/opik-frontend/src/components/pages/DashboardPage/
-DashboardPage.tsx - // Connect to backend API
  DashboardHeader.tsx - // NEW: Header with actions
  DashboardGlobalFilters.tsx; // NEW: Global date range filter only
```

**Features:**

- Load dashboard from backend by ID
- Auto-save changes (debounced, 2-3 seconds)
- Show save status (saving/saved/error)
- Edit dashboard name/description inline
- Global date range selector (applies to all widgets)
- Back to dashboards list

**Effort:** 2 days (backend integration, auto-save, header)

---

#### Phase 1: Layout System (Week 1-2 - 3 days)

**Section Management:**

```typescript
// ENHANCE: apps/opik-frontend/src/components/pages/DashboardPage/
-DashboardSection.tsx - // Add section actions
  DashboardSectionHeader.tsx - // NEW: Section controls
  AddSectionButton.tsx; // Improve styling
```

**Features:**

- Add new section
- Edit section title
- Delete section (with confirmation)
- Reorder sections (drag-drop)
- Collapse/expand sections
- Section-level settings (future: filters)

**Effort:** 1.5 days

---

**Widget Management:**

```typescript
// ENHANCE: apps/opik-frontend/src/components/pages/DashboardPage/
-DashboardWidget.tsx - // Add widget actions
  DashboardWidgetGrid.tsx - // Improve grid behavior
  AddWidgetDialog.tsx - // Complete widget catalog
  EditWidgetDialog.tsx; // NEW: Edit widget config
```

**Features:**

- Add widget to section
- Edit widget configuration (including per-widget project selector)
- Delete widget (with confirmation)
- Move widget between sections
- Resize widget (grid layout)
- Drag-drop widget positioning
- Per-widget date range override option
- Widget error boundaries

**Effort:** 1.5 days

---

#### Phase 1.5: Widget Configuration Dialog (Week 1-2 - 1.5 days)

**⚠️ Critical Component - Built BEFORE First Widgets**

This reusable dialog provides a consistent configuration experience for all 16 widgets. Must be built first as all widgets depend on it.

**Component Structure:**

```typescript
// NEW: apps/opik-frontend/src/components/pages/DashboardPage/
-WidgetConfigDialog.tsx - // Main dialog component
  WidgetConfigForm.tsx - // Left panel: settings form
  WidgetConfigPreview.tsx - // Right panel: live preview
  widgetConfigSchemas.ts; // Schema definitions for each widget type
```

**Dialog Layout (Two-Panel Design):**

```
┌─────────────────────────────────────────────────────────┐
│  Add widget                                          ✕  │
├──────────────────────┬──────────────────────────────────┤
│                      │                                  │
│  Left Panel:         │  Right Panel:                    │
│  Settings Form       │  Live Widget Preview             │
│                      │                                  │
│  • Name              │  [Real-time preview]             │
│  • Metric dropdown   │                                  │
│  • Filters           │  Shows actual data from API      │
│  • Scope (project)   │                                  │
│  • Chart type        │  Updates as user changes         │
│  • Date range        │  settings (debounced 300ms)      │
│                      │                                  │
├──────────────────────┴──────────────────────────────────┤
│                          Cancel   [Add widget]          │
└─────────────────────────────────────────────────────────┘
```

**Component API:**

```typescript
interface WidgetConfigDialogProps {
  widgetType: WidgetType;
  initialConfig?: WidgetConfig;
  mode: "create" | "edit";
  onSave: (config: WidgetConfig) => void;
  onCancel: () => void;
}

<WidgetConfigDialog
  widgetType="feedback_scores_chart"
  initialConfig={existingConfig}
  mode="edit"
  onSave={handleSave}
  onCancel={handleCancel}
/>;
```

**Left Panel - Settings Form:**

Dynamic form based on widget type schema:

```typescript
// Widget schema example
const CHART_WIDGET_SCHEMA: WidgetConfigSchema = {
  fields: [
    { name: 'name', type: 'text', label: 'Widget name', required: true },
    { name: 'metric', type: 'select', label: 'Metric', options: [...], required: true },
    { name: 'projectId', type: 'project-selector', label: 'Project', required: true },
    { name: 'chartType', type: 'radio', label: 'Chart type', options: ['line', 'bar', 'area'] },
    { name: 'dateRange', type: 'date-range-picker', label: 'Date range' },
    { name: 'filters', type: 'filter-builder', label: 'Filters', optional: true },
  ]
};
```

**Right Panel - Live Preview:**

```typescript
// Preview component fetches real data
function WidgetConfigPreview({ widgetType, config }) {
  const debouncedConfig = useDebounce(config, 300);
  const { data, isLoading, error } = useWidgetData(widgetType, debouncedConfig);

  return (
    <div className="preview-pane">
      {isLoading && <WidgetSkeleton />}
      {error && <PreviewError error={error} />}
      {data && <WidgetRenderer type={widgetType} config={config} data={data} />}
    </div>
  );
}
```

**Why Build This First:**

1. ✅ **Consistency** - All 16 widgets use the same config pattern
2. ✅ **UX** - Users see live preview before committing
3. ✅ **Speed** - Widget implementation becomes schema definition + render logic
4. ✅ **Quality** - Catches config errors immediately
5. ✅ **Reusability** - Used for Add + Edit flows

**Effort:** 1.5 days (one-time investment, reused for all widgets)

---

#### Phase 2: Widget Framework (Week 2 - 1 day)

**Widget Type System:**

```typescript
// NEW: apps/opik-frontend/src/components/pages/DashboardPage/widgets/
-WidgetFactory.tsx - // Widget renderer factory
  BaseWidget.tsx - // Base widget component
  WidgetErrorBoundary.tsx - // Error handling
  WidgetSkeleton.tsx; // Loading skeleton
```

**Widget Registry:**

```typescript
const WIDGET_TYPES = {
  stat_card: StatCardWidget,
  chart: ChartWidget,
  experiment_chart: ExperimentChartWidget,
  text: TextWidget,
  cost_summary: CostSummaryWidget,
  // ... 16 total types
};
```

**Effort:** 1 day (framework, registry, error handling - reduced from 2 days because config dialog is separate)

---

### 0.3 Dashboard Schema & Data Management

**⚠️ Version Management Decision:**

Version information is stored **inside the `config` JSON field** as `schemaVersion`. There is **no separate top-level `version` field** in the backend API. This approach:

- ✅ Requires **zero backend changes** (aligns with MVP goal)
- ✅ Keeps version management purely frontend-owned
- ✅ Allows unlimited schema evolution without backend updates
- ⚠️ Version not visible at database level (can't filter/sort by version)

#### FE/BE Responsibility Split

**Frontend Responsibilities:**

- ✅ **Define dashboard structure** - FE owns the schema, widget types, configuration format
- ✅ **Data migration** - FE handles version upgrades when loading dashboards
- ✅ **Schema versioning** - FE manages `schemaVersion` inside `config` JSON
- ✅ **Validation** - FE validates dashboard JSON before saving
- ✅ **Default dashboards** - FE manages default dashboard assignments

**Backend Responsibilities:**

- ✅ **Agnostic storage** - BE stores dashboard JSON as opaque payload in `config` field
- ✅ **CRUD operations** - BE provides create/read/update/delete endpoints
- ✅ **No validation** - BE doesn't validate or parse `config` structure (completely opaque)
- ✅ **Metadata** - BE manages timestamps, ownership, workspace association, auto-generated slug

#### Dashboard Version Management

**Schema Version:**

```typescript
interface Dashboard {
  id: string;
  name: string;
  description?: string;
  config: DashboardConfig; // ✅ FE-owned structure (opaque to BE, includes version)
  metadata: {
    workspaceId: string;
    createdAt: string;
    updatedAt: string;
    createdBy: string;
  };
}

interface DashboardConfig {
  schemaVersion: number; // ✅ FE manages version inside config (no separate version field)
  globalFilters: {
    dateRange?: { from: string; to: string };
  };
  sections: Section[];
}
```

**Version Migration Example:**

```typescript
// FE: apps/opik-frontend/src/utils/dashboardMigration.ts
export function migrateDashboard(dashboard: Dashboard): Dashboard {
  const currentVersion = 2; // Latest schema version

  if (dashboard.config.schemaVersion === currentVersion) {
    return dashboard; // No migration needed
  }

  let config = dashboard.config;

  // Migrate v1 → v2
  if (config.schemaVersion === 1) {
    config = migrateV1ToV2(config);
  }

  // Future migrations...
  // if (config.schemaVersion === 2) {
  //   config = migrateV2ToV3(config);
  // }

  return {
    ...dashboard,
    config: {
      ...config,
      schemaVersion: currentVersion, // ✅ Update schema version inside config
    },
  };
}
```

**When Migration Happens:**

1. **Loading dashboard** - Check version, migrate if needed, save back to BE
2. **Template selection** - Apply migrations before creating new dashboard
3. **Import dashboard** - Validate and migrate imported JSON

#### Backend API Flexibility

**BE allows FE to update config freely:**

```typescript
// PATCH /v1/private/dashboards/{id}
{
  "name": "My Dashboard",    // ✅ Optional - update dashboard name
  "description": "...",       // ✅ Optional - update description
  "config": {                 // ✅ FE can rewrite entire config (including version)
    "schemaVersion": 2,       // ✅ Version managed inside config
    "globalFilters": { ... },
    "sections": [ ... ]
  }
}
```

**BE validation is minimal:**

- ✅ Valid JSON
- ✅ Required fields exist (`name`, `config`)
- ✅ Size constraints (name: 1-120 chars, description: max 1000 chars)
- ❌ No validation of `config` structure (FE-owned)
- ❌ No validation of widget types (FE-owned)
- ❌ No validation of `schemaVersion` inside config

#### Default Dashboard Assignment (To Be Investigated)

**Requirement:** Mark dashboards as default for specific contexts:

- Default dashboard for a **project**
- Default dashboard for an **experiment** (used in comparison view)
- Default dashboard for **workspace** (shown on dashboard list)

**Proposed Approaches (Need Investigation):**

**Option 1: Workspace-level metadata**

```typescript
// Store in workspace settings
interface WorkspaceSettings {
  defaultDashboards: {
    projectId?: string; // Default dashboard for project view
    experimentId?: string; // Default for experiment comparison
    workspaceDefault?: string; // Default for workspace
  };
}
```

**Option 2: Dashboard metadata flag**

```typescript
interface Dashboard {
  // ...
  defaultFor?: {
    type: "project" | "experiment" | "workspace";
    entityId?: string; // Optional: specific project/experiment ID
  };
}
```

**Option 3: Separate association table**

```sql
CREATE TABLE default_dashboards (
  id UUID PRIMARY KEY,
  workspace_id UUID NOT NULL,
  dashboard_id UUID NOT NULL,
  context_type VARCHAR(50) NOT NULL,  -- 'project', 'experiment', 'workspace'
  context_id UUID,                    -- Optional: specific entity ID
  UNIQUE(workspace_id, context_type, context_id)
);
```

**⚠️ TODO - Phase 1.5 (After MVP):**

- Investigate best approach for default dashboard assignment
- Consider user preferences vs workspace defaults
- Design UI for setting defaults (per-project, per-experiment)
- Implement BE support if needed (likely small change)

**Estimated Effort:** 2-3 days (investigation + implementation)

---

### 0.4 Incremental Delivery Phases

**Delivery-Focused Phases** (based on user value, not technical layers)

| Phase       | What Users Get                                      | Days           | Deliverable?            | Status                          |
| ----------- | --------------------------------------------------- | -------------- | ----------------------- | ------------------------------- |
| **Phase 0** | Infrastructure (dashboard list, grid, framework)    | **5**          | ❌ No user value        | Foundation only                 |
| **Phase 1** | Config dialog + 3 fully functional widgets          | **4**          | ✅ **DEMO READY**       | Live preview + working widgets  |
| **Phase 2** | All 9 metric charts (same component)                | **2.75**       | ✅ **PRODUCTION READY** | Complete metrics with settings  |
| **Phase 3** | Experiments (3) + Cost Trend (all fully configured) | **2.25**       | ✅ **FEATURE COMPLETE** | All 16 widgets production-ready |
| **Phase 4** | Testing, docs, performance optimization             | **2.75**       | ✅ **MVP LAUNCH**       | Polished and tested             |
|             | **TOTAL:**                                          | **16.75 days** |                         | **~3-4 weeks**                  |

**Timeline for 1 Developer:**

- **With AI Assistance (Cursor/Copilot):** 16.75 days → **~3-4 weeks calendar time** ✅ (Recommended)
- **Manual coding:** 24 days → ~5 weeks calendar time

**Incremental Delivery Benefits:**

- ✅ Early demo after Week 1 (Phase 1 complete - config dialog + 3 widgets with settings)
- ✅ Production-ready metrics after Week 2 (Phase 2 complete - all 9 charts fully configured)
- ✅ Feature complete after Week 3 (Phase 3 complete - all 16 widgets production-ready)
- ✅ Can stop/pivot based on early feedback
- ✅ Risk mitigation through incremental validation
- ✅ Every phase (except Phase 0) delivers working, usable features

**Key Architectural Decision:**

**Widget Configuration Built Into Each Widget (Not Separate Phase)**

- ✅ **Reusable config dialog** built in Phase 1 (1.5 days one-time investment)
- ✅ **Per-widget settings** (filters, date range, chart type) built with each widget
- ✅ **Live preview** shows actual data during configuration
- ✅ **No "Polish Phase"** for adding configs later - widgets are complete when implemented
- ✅ **User value from Day 1** - each widget is immediately usable after implementation

**Why Build Config First?**

- ✅ **Consistency** - All 16 widgets use same config pattern
- ✅ **Speed** - Widget implementation becomes faster (schema + render)
- ✅ **Quality** - Users can test settings immediately
- ✅ **UX** - Live preview prevents configuration errors

**AI Speed-up (~40%):** Boilerplate, components, types, hooks, tests, config forms  
**With 3 developers:** ~2-3 weeks calendar time

---

## 1. General Dashboard Features

### 1.1 Widget-Level Filtering ⭐⭐⭐⭐⭐

**Description:** Add filtering capabilities to individual widgets

**Backend Support:** ✅ Already exists

- API: `POST /projects/{id}/metrics`
- Parameters: `trace_filters`, `thread_filters`

**Features:**

- Filter by tags
- Filter by metadata
- Filter by model/provider
- Filter by date ranges
- Filter by feedback scores
- Save filters per widget

**Implementation:**

- Add filter UI to widget header
- Store filters in widget config
- Pass filters to existing API endpoints

**Complexity:** Frontend only  
**Value:** HIGH - Users can customize widget scope  
**Performance:** ⚡ Fast - backend handles filtering

---

### 1.2 Multiple Chart Types ⭐⭐⭐⭐⭐

**Description:** Support different visualization types for metric data

**Backend Support:** ✅ Already exists - same data, different rendering

**Chart Types:**

- Line chart (currently implemented)
- Bar chart (component exists: `MetricBarChart`)
- Area chart
- Stacked area chart
- Stacked bar chart

**Implementation:**

- Add chart type selector to widget config
- Reuse existing chart components
- Pass same data to different chart types

**Complexity:** Frontend only  
**Value:** HIGH - Better data visualization  
**Performance:** ⚡ Fast - same data payload

---

### 1.3 Per-Widget Date Ranges ⭐⭐⭐⭐⭐

**Description:** Individual date range controls per widget

**Backend Support:** ✅ Already exists

- API accepts `interval_start`, `interval_end` parameters

**Features:**

- Override global date range per widget
- Quick presets (reuse existing DateRangeSelect: Past 24h, 3d, 7d, 30d, 60d, All time)
- Period-over-period comparison
- Custom date picker

**Implementation:**

- Add date range selector to widget config
- Store range in widget config
- Pass custom dates to API calls

**Complexity:** Frontend only  
**Value:** HIGH - Flexible time analysis  
**Performance:** ⚡ Fast - backend handles date filtering

---

### 1.4 Dashboard Persistence ⭐⭐⭐⭐⭐

**Description:** Save and load dashboards using backend API

**Backend Support:** ✅ **Already exists!**

**Available Endpoints:**

```
POST   /v1/private/dashboards          - Create dashboard
GET    /v1/private/dashboards/{id}     - Get dashboard by ID
GET    /v1/private/dashboards          - List dashboards (paginated)
PATCH  /v1/private/dashboards/{id}     - Update dashboard
DELETE /v1/private/dashboards/{id}     - Delete dashboard
```

**Dashboard Model:**

```json
{
  "id": "uuid",
  "workspace_id": "workspace-123",
  "name": "Production Metrics",
  "slug": "production-metrics",
  "description": "Key metrics for production environment",
  "config": {                      // ✅ FE-owned structure (opaque to BE, includes version)
    "schemaVersion": 2,            // ✅ FE tracks schema version inside config
    "globalDateRange": { "preset": "past30days" },
    "sections": [ ... ]
  },
  "created_by": "user@example.com",
  "created_at": "2025-01-20T10:00:00Z",
  "last_updated_by": "user@example.com",
  "last_updated_at": "2025-01-21T14:30:00Z"
}
```

**⚠️ Important: Backend is Agnostic**

- **BE does NOT validate** `config` structure - stores as opaque JSON
- **FE owns** the schema, widget types, and validation
- **FE handles** version migrations when loading dashboards (via `config.schemaVersion`)
- **BE allows** FE to update `config` freely (including `schemaVersion` inside it)

**Features:**

- Save/load dashboards from backend
- List all dashboards in workspace
- Update dashboard configuration (including version)
- Delete dashboards
- Auto-save on changes (debounced)

**Implementation:**

- Use existing CRUD endpoints
- Store all dashboard state in `config` JSON field
- FE applies migrations on load (see section 0.3)
- Add auto-save with debouncing (2-3 seconds)
- Show last saved timestamp

**Complexity:** Easy - API already exists  
**Value:** HIGH - Persistent dashboards  
**Performance:** ⚡ Fast - standard CRUD operations

---

## 2. Stats Widgets

### 2.1 Stat Card

**API Endpoints:**

```typescript
// Option 1: Projects Stats
GET /v1/private/projects/stats?page=1&size=10

Response: {
  content: [{
    project_id: "uuid",
    total_estimated_cost: 456.78,
    feedback_scores: [{ name: "accuracy", value: 0.92 }],
    duration: { p50: 120.5, p90: 250.3, p99: 450.7 },
    usage: { prompt_tokens: 123456, completion_tokens: 67890, total_tokens: 191346 }
  }]
}

// Option 2: Traces Stats
GET /v1/private/traces/stats?project_id={uuid}

Response: {
  trace_count: 1234,
  total_estimated_cost: 456.78,
  feedback_scores: [...],
  duration: { p50: 120, p90: 250, p99: 450 }
}

// Option 3: Spans Stats
GET /v1/private/spans/stats?project_id={uuid}

Response: {
  span_count: 5678,
  llm_usage: { prompt_tokens: 234567, completion_tokens: 123456 },
  error_rate: 0.023,
  duration: { p50: 45, p90: 120, p99: 250 }
}
```

**Component:**

```typescript
// apps/opik-frontend/src/components/pages/DashboardPage/widgets/StatCard.tsx
interface StatCardConfig {
  source: "projects" | "traces" | "spans";
  projectId?: string;
  metric: string; // e.g., "total_estimated_cost", "trace_count", "feedback_scores.accuracy"
  showTrend: boolean;
  dateRange?: { from: string; to: string };
}

// Trend calculation
const percentageChange = ((current - previous) / previous) * 100;
const trend = current > previous ? "up" : "down";
```

**Implementation:** 0.5 day

---

## 3. Text Widgets

### 3.1 Text/Markdown Widget

**Backend API:** None (frontend only)

**Implementation Status:** ✅ **COMPLETED**

**Components Created:**

```typescript
// Widget Component
// apps/opik-frontend/src/components/shared/Dashboard/widgets/TextMarkdownWidget/TextMarkdownWidget.tsx
// - Uses existing MarkdownPreview component for rendering
// - Uses existing DashboardWidget compound components (Header, Actions, Content, EmptyState)
// - Handles empty state when no content exists
// - Includes all standard widget actions (edit, delete, duplicate, move, drag)
// - Supports preview mode: preview={true} hides actions and uses previewData prop

// Editor Component
// apps/opik-frontend/src/components/shared/Dashboard/widgets/TextMarkdownWidget/TextMarkdownEditor.tsx
// - Uses CodeMirror for markdown editing
// - Includes title and subtitle fields
// - Uses useCodemirrorTheme for consistent theming
// - No inline preview (preview shown only on right side of dialog)
```

**Registration & Preview:**

```typescript
// Updated: apps/opik-frontend/src/components/shared/Dashboard/widgets/widgetRegistry.tsx
// - Registered TextMarkdownWidget and TextMarkdownEditor
// - Widget selector: apps/opik-frontend/src/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialogAddStep.tsx
// - Already includes TEXT_MARKDOWN widget option (enabled by default)

// Updated: apps/opik-frontend/src/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigPreview.tsx
// - Renders actual widget component in preview mode
// - Uses widgetResolver to get Widget component
// - Reads preview widget from DashboardStore instead of props
// - Live preview shows real widget rendering as user configures it
// - Preview mode renders only widget content (no header, actions, or wrapper)

// Updated: DashboardStore
// - Added previewWidget state to store widget being configured
// - Added setPreviewWidget/getPreviewWidget actions
// - Added selectPreviewWidget/selectSetPreviewWidget selectors
// - WidgetConfigDialog updates store as user edits widget config
// - Store cleaned up when dialog closes

// Updated: DashboardWidgetComponentProps interface
// - Added optional preview?: boolean prop
// - Made sectionId and widgetId optional for preview mode
// - Removed previewData prop (now read from store)
```

**Widget Config Structure:**

```json
{
  "type": "text_markdown",
  "config": {
    "content": "# Dashboard Overview\n\nThis dashboard tracks:\n- **Cost**\n- **Traces**"
  }
}
```

**Dependencies (Already Installed):**

- `react-markdown` - For rendering markdown preview (via MarkdownPreview)
- `@uiw/react-codemirror` - For markdown editing
- Note: Markdown syntax highlighting not enabled (would require `@codemirror/lang-markdown` package)
- Plain text editing mode used instead - fully functional

**Implementation:** 0.5 day ✅ (Completed)

---

### 3.2 Project Metrics Chart Widget

**Implementation Status:** ✅ **COMPLETED**

**Components:**

```typescript
// Widget Component
// apps/opik-frontend/src/components/shared/Dashboard/widgets/ProjectMetricsWidget/ProjectMetricsWidget.tsx
// - Renders metric charts using MetricContainerChart with chartOnly mode
// - Supports both trace and thread metrics
// - Passes filters and chart type to chart component
// - Uses DashboardWidget compound components

// Editor Component
// apps/opik-frontend/src/components/shared/Dashboard/widgets/ProjectMetricsWidget/ProjectMetricsEditor.tsx
// - Complete configuration form with:
//   - Widget title and subtitle fields
//   - Project selector (optional, falls back to dashboard project)
//   - Metric type selector (all 9 metric types)
//   - Chart type selector (line/bar)
//   - Use global date range toggle
//   - Local date range picker (shown when toggle is OFF, uses MetricDateRangeSelect)
//   - Filters section (trace or thread filters based on metric type)
//   - Filter UI matches AddEditRule dialog pattern
```

**Configuration Options:**

1. **Metric Types** - 9 options:

   - Trace Feedback Scores
   - Number of Traces
   - Trace Duration
   - Token Usage
   - Estimated Cost
   - Failed Guardrails
   - Number of Threads
   - Thread Duration
   - Thread Feedback Scores

2. **Chart Types** - 2 options:

   - Line Chart
   - Bar Chart

3. **Date Range** - Toggle:

   - Use dashboard global date range (default: ON)
   - Use widget-specific date range (with MetricDateRangeSelect picker)

4. **Filters** - Full filtering support:
   - Trace filters (for trace-based metrics)
   - Thread filters (for thread-based metrics)
   - Filter by: ID, name, time, input, output, duration, metadata, tags, feedback scores, custom paths
   - Same filter UI as used in Rules and Metrics Tab

**Widget Config Structure:**

```typescript
{
  type: WidgetType.CHART_METRIC,
  config: {
    projectId?: string;  // Optional project ID (falls back to dashboard project)
    metricType: "TRACE_COUNT",
    chartType: "line",
    useGlobalDateRange: true,
    dateRange?: DateRangeValue;  // Local date range when useGlobalDateRange is false
    traceFilters: [
      { field: "tags", operator: "=", value: "production" }
    ]
  }
}
```

**Implementation:** 1 day ✅ (Completed)

---

**Files Created:**

- `/apps/opik-frontend/src/components/shared/Dashboard/constants.ts` - Dashboard widget constants (default titles)
- `/apps/opik-frontend/src/components/shared/Dashboard/widgets/TextMarkdownWidget/TextMarkdownWidget.tsx` - Text widget display component
- `/apps/opik-frontend/src/components/shared/Dashboard/widgets/TextMarkdownWidget/TextMarkdownEditor.tsx` - Text widget configuration editor
- `/apps/opik-frontend/src/components/shared/Dashboard/DashboardWidget/DashboardWidgetPreviewContent.tsx` - Preview mode wrapper component

**Files Updated:**

- `/apps/opik-frontend/src/components/shared/Dashboard/widgets/widgetRegistry.tsx` - Registered TextMarkdown and ProjectMetrics widgets
- `/apps/opik-frontend/src/components/shared/Dashboard/widgets/ProjectMetricsWidget/ProjectMetricsWidget.tsx` - Fixed imports, added preview mode, filters support, chart type support, project selector support, local date range support
- `/apps/opik-frontend/src/components/shared/Dashboard/widgets/ProjectMetricsWidget/ProjectMetricsEditor.tsx` - Complete configuration editor with project selector (ProjectsSelectBox), metric selector (SelectBox), chart type (SelectBox), date range toggle, local date range picker (MetricDateRangeSelect), and filters (FiltersContent)
- `/apps/opik-frontend/src/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigPreview.tsx` - Renders actual widget using store
- `/apps/opik-frontend/src/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialog.tsx` - Updates preview widget in store on config changes, uses DEFAULT_WIDGET_TITLES from constants
- `/apps/opik-frontend/src/components/shared/Dashboard/WidgetConfigDialog/WidgetConfigDialogAddStep.tsx` - Added ProjectMetric chart to widget selector
- `/apps/opik-frontend/src/components/shared/Dashboard/DashboardWidget/index.ts` - Registered PreviewContent component
- `/apps/opik-frontend/src/components/pages/TracesPage/MetricsTab/MetricChart/MetricChartContainer.tsx` - Added chartOnly mode
- `/apps/opik-frontend/src/store/DashboardStore.ts` - Added preview widget state and actions
- `/apps/opik-frontend/src/types/dashboard.ts` - Updated ChartMetricWidget config with projectId, filters, chartType, useGlobalDateRange, and dateRange (DateRangeValue)

**Files Deleted:**

- `/apps/opik-frontend/src/components/pages/DashboardPage/AddWidgetDialog.tsx` - Removed obsolete file (replaced by WidgetConfigDialog system)
- `/apps/opik-frontend/src/components/shared/DashboardWidget/` - Removed duplicate directory (correct location is `/apps/opik-frontend/src/components/shared/Dashboard/DashboardWidget/`)

---

## 4. Project Metric Charts (9 Widgets)

**API:**

```typescript
POST /v1/private/projects/{id}/metrics

Request: {
  metric_type: "FEEDBACK_SCORES" | "TRACE_COUNT" | "TOKEN_USAGE" | "DURATION" |
               "COST" | "GUARDRAILS_FAILED_COUNT" | "THREAD_COUNT" |
               "THREAD_DURATION" | "THREAD_FEEDBACK_SCORES",
  interval: "HOURLY" | "DAILY" | "WEEKLY",
  interval_start: "2025-01-01T00:00:00Z",
  interval_end: "2025-01-31T23:59:59Z",
  trace_filters: [{ field: string, operator: string, value: string }],
  thread_filters: [...]
}

Response: {
  project_id: "uuid",
  metric_type: string,
  interval: string,
  results: [{
    name: string,
    data: [{ time: string, value: number }]
  }]
}
```

**Component:**

```typescript
// apps/opik-frontend/src/components/pages/DashboardPage/widgets/ProjectMetricChart.tsx
interface ProjectMetricChartProps {
  metricType: MetricType;
  projectId: string;
  interval: "HOURLY" | "DAILY" | "WEEKLY";
  chartType: "line" | "bar" | "area"; // Support all 3 types from day 1
}

// Reuse existing: MetricContainerChart, MetricBarChart, HomePageChart
// Build chart type switcher component
```

**Widget Registry:**

```typescript
const WIDGET_TYPES = {
  feedback_scores_chart: { metricType: "FEEDBACK_SCORES" },
  trace_count_chart: { metricType: "TRACE_COUNT" },
  token_usage_chart: { metricType: "TOKEN_USAGE" },
  duration_chart: { metricType: "DURATION" },
  cost_chart: { metricType: "COST" },
  guardrails_failed_chart: { metricType: "GUARDRAILS_FAILED_COUNT" },
  thread_count_chart: { metricType: "THREAD_COUNT" },
  thread_duration_chart: { metricType: "THREAD_DURATION" },
  thread_feedback_scores_chart: { metricType: "THREAD_FEEDBACK_SCORES" },
};
```

**Implementation:**

- **Base component with 3 chart types:** 1.5 days (build reusable component supporting line/bar/area)
- **Remaining 8 widgets:** 2 days (0.25 each - just schema definitions)

---

## 5. Experiment Widgets (3 Widgets)

### 5.1 Experiment Feedback Scores Chart

**API:**

```typescript
GET /
  v1 /
  private /
  datasets /
  { datasetId } /
  experiments /
  { experimentId } /
  feedback -
  scores;
```

**Existing Component:**

```typescript
// apps/opik-frontend/src/components/pages-shared/experiments/FeedbackScoresChartsWrapper/
import { FeedbackScoresChartsWrapper } from "@/components/pages-shared/experiments";
import { useExperimentsFeedbackScoresNames } from "@/api/datasets/useExperimentsFeedbackScoresNames";
```

**Implementation:** 1 day (wrap existing component + config)

---

### 5.2 Experiment Radar Chart

**Existing Component:**

```typescript
// apps/opik-frontend/src/components/pages-shared/experiments/ExperimentsRadarChart/
import { ExperimentsRadarChart } from "@/components/pages-shared/experiments";
import { useCompareExperimentsChartsData } from "@/api/datasets/useCompareExperimentsChartsData";
```

**Implementation:** 0.5 day

---

### 5.3 Experiment Bar Chart

**Existing Component:**

```typescript
// apps/opik-frontend/src/components/pages-shared/experiments/ExperimentsBarChart/
import { ExperimentsBarChart } from "@/components/pages-shared/experiments";
import { useCompareExperimentsChartsData } from "@/api/datasets/useCompareExperimentsChartsData";
```

**Implementation:** 0.5 day

**Total:** 2 days

---

## 6. Cost Widgets (2 Widgets)

### 6.1 Cost Summary Card

**API:**

```typescript
POST /v1/private/workspaces/costs/summaries

Request: {
  project_ids: string[],
  interval_start: string,
  interval_end: string
}

Response: {
  results: [{
    name: "cost",
    current: number,
    previous: number
  }]
}
```

**Component:**

```typescript
const percentageChange = ((current - previous) / previous) * 100;
const trend = current > previous ? "up" : "down";
```

**Implementation:** 0.5 day

---

### 6.2 Cost Trend Chart

**API:**

```typescript
POST /v1/private/workspaces/costs

Request: {
  project_ids: string[],
  interval_start: string,
  interval_end: string
}

Response: {
  results: [{
    project_id: string,
    name: "cost",
    data: [{ time: string, value: number }]
  }]
}
```

**Component:** Reuse existing line chart components

**Implementation:** 1 day

**Total:** 1.5 days

---

## 7. Backend API Reference

### 7.1 Metrics Endpoints

#### Project Metrics (Time-Series)

```
POST /v1/private/projects/{id}/metrics
```

**Request Body:**

```json
{
  "metric_type": "TRACE_COUNT | TOKEN_USAGE | COST | DURATION | FEEDBACK_SCORES | GUARDRAILS_FAILED_COUNT | THREAD_COUNT | THREAD_DURATION | THREAD_FEEDBACK_SCORES",
  "interval": "HOURLY | DAILY | WEEKLY",
  "interval_start": "2025-01-01T00:00:00Z",
  "interval_end": "2025-01-31T23:59:59Z",
  "trace_filters": [
    {
      "field": "tags",
      "operator": "=",
      "value": "production"
    }
  ],
  "thread_filters": [...]
}
```

**Response:**

```json
{
  "project_id": "uuid",
  "metric_type": "TRACE_COUNT",
  "interval": "DAILY",
  "results": [
    {
      "name": "metric_name",
      "data": [
        { "time": "2025-01-01T00:00:00Z", "value": 123 },
        { "time": "2025-01-02T00:00:00Z", "value": 145 }
      ]
    }
  ]
}
```

---

### 7.2 Stats Endpoints

````typescript
// Project Stats
GET /v1/private/projects/stats?page=1&size=10&name=project-name

Response: {
  content: [{
    project_id: string,
    feedback_scores: [{ name: string, value: number }],
    duration: { p50: number, p90: number, p99: number },
    total_estimated_cost: number,
    total_estimated_cost_sum: number,
    usage: { prompt_tokens: number, completion_tokens: number, total_tokens: number },
    trace_count: number,
    guardrails_failed_count: number,
    error_count: { current: number, previous: number }
  }]
}

// Trace Stats
GET /v1/private/traces/stats?project_id={uuid}&from_time={iso}&to_time={iso}

Response: {
  stats: [{
    name: "trace_count" | "duration" | "total_estimated_cost",
    value: number | { p50: number, p90: number, p99: number },
    type: "COUNT" | "PERCENTAGE" | "AVG"
  }]
}

// Span Stats
GET /v1/private/spans/stats?project_id={uuid}&from_time={iso}&to_time={iso}

Response: {
  stats: [{
    name: "span_count" | "llm_usage" | "error_rate" | "duration",
    value: number | object,
    type: "COUNT" | "USAGE" | "PERCENTAGE"
  }]
}

---

### 7.3 Cost Endpoints

```typescript
// Cost Summary
POST /v1/private/workspaces/costs/summaries

Request: {
  project_ids: string[],
  interval_start: string,
  interval_end: string
}

Response: {
  results: [{
    name: "cost",
    current: number,
    previous: number
  }]
}

// Cost Time-Series
POST /v1/private/workspaces/costs

Request: {
  project_ids: string[],
  interval_start: string,
  interval_end: string
}

Response: {
  results: [{
    project_id: string,
    name: "cost",
    data: [{ time: string, value: number }]
  }]
}

---

## 8. Phase 2 Preview (Out of MVP Scope)

**Table Widgets** (see `dashboard-widgets-next-steps.md` for details):
- Experiments ranking table
- Traces ranking table
- Projects comparison table
- Datasets listing table

**Required Endpoints:**
```typescript
GET /v1/private/traces?sorting=total_estimated_cost:desc&page=1&size=10
GET /v1/private/traces?sorting=feedback_scores.{scoreName}:desc
GET /v1/private/experiments?sorting={field}:desc
GET /v1/private/datasets?sorting={field}:desc
````

---

## Appendix B: Technology Stack

### Frontend

- **Framework:** React 18.3.1
- **Build Tool:** Vite 5.2.11
- **State Management:** Zustand 4.5.2
- **Data Fetching:** TanStack Query 5.45.0
- **Charts:** Recharts
- **UI Components:** Radix UI
- **Styling:** Tailwind CSS 3.4.3
- **Layout:** React Grid Layout

### Backend

- **Framework:** Dropwizard 4.0.14
- **Language:** Java 21
- **Database:** MySQL 9.3.0 + ClickHouse
- **API Style:** REST

### Testing

- **Unit Tests:** Vitest 3.0.5
- **E2E Tests:** Playwright 1.45.3

---

## Appendix C: Dashboard Architecture

### Simplified Architecture (No Section Filters)

```typescript
interface Dashboard {
  id: string;
  name: string;

  // Global date range applied to all widgets by default
  globalDateRange: {
    start: string;
    end: string;
    preset:
      | "past24hours"
      | "past3days"
      | "past7days"
      | "past30days"
      | "past60days"
      | "alltime"
      | "custom";
  };

  sections: DashboardSection[];
  lastModified: number;
  version: number;
}

interface DashboardSection {
  id: string;
  title: string;
  expanded: boolean;

  widgets: DashboardWidget[];
  layout: DashboardLayout;
}

interface DashboardWidget {
  id: string;
  type: string; // "stat_card" | "chart" | "table" | etc.
  title: string;

  // Metric config (for metric-based widgets)
  metricType?: METRIC_NAME_TYPE;

  // Widget-specific config
  config: {
    // Date range override
    useGlobalDateRange?: boolean; // default: true
    customDateRange?: {
      start: string;
      end: string;
      preset?: "24h" | "7d" | "30d" | "90d" | "custom";
    };

    // Project filtering (per-widget only, no global)
    projectId?: string; // Single project for project-scoped widgets
    projectIds?: string[]; // Multiple projects for workspace-scoped widgets (e.g., cost)

    // Filters
    traceFilters?: Filter[];
    threadFilters?: Filter[];

    // Experiment-specific
    experimentIds?: string[];
    datasetId?: string;

    // Chart-specific
    chartType?: "line" | "bar" | "area" | "stacked";

    // Display options
    showTrend?: boolean;
    showSparkline?: boolean;
    format?: "number" | "currency" | "percentage" | "duration";
    colorScheme?: string;
    threshold?: number;
    // ... widget-specific options
  };
}
```

### Filter Application Logic

```typescript
function getEffectiveDateRange(
  widget: DashboardWidget,
  globalDateRange: DateRange
): DateRange {
  if (
    widget.config.useGlobalDateRange === false &&
    widget.config.customDateRange
  ) {
    return widget.config.customDateRange;
  }
  return globalDateRange;
}

// Project filtering is per-widget only
function getWidgetProjectFilter(widget: DashboardWidget): {
  projectId?: string;
  projectIds?: string[];
} {
  return {
    projectId: widget.config.projectId,
    projectIds: widget.config.projectIds,
  };
}
```

### Key Design Decisions

**Why No Section Filters?**

- ✅ **Simpler architecture**: Only 2 levels (global + widget)
- ✅ **Less confusion**: Clear hierarchy
- ✅ **More flexible**: Each widget can customize independently
- ✅ **Easier to implement**: No intermediate layer logic
- ✅ **Better UX**: Global controls + per-widget overrides are intuitive

**Filter Precedence:**

```
Widget config > Global filters
```

**Example Use Cases:**

```typescript
// Use Case 1: All widgets use global date range
{
  globalDateRange: { preset: "30d" },
  sections: [
    {
      widgets: [
        {
          type: "cost_chart",
          config: {
            projectIds: ["prod", "staging"], // Per-widget project filter
          },
        },
        {
          type: "trace_count",
          config: {
            projectId: "prod", // Single project for trace widget
          },
        },
      ],
    },
  ];
}

// Use Case 2: One widget overrides date range
{
  globalDateRange: { preset: "30d" },
  sections: [
    {
      widgets: [
        {
          type: "cost_trend",
          config: {
            useGlobalDateRange: true, // Uses 30d
            projectIds: ["prod"],
          },
        },
        {
          type: "cost_comparison",
          config: {
            useGlobalDateRange: false,
            customDateRange: { preset: "90d" }, // Overrides to 90d
            projectIds: ["prod", "staging"],
          },
        },
      ],
    },
  ];
}

// Use Case 3: Multi-section dashboard with different projects
{
  globalDateRange: { preset: "7d" },
  sections: [
    {
      title: "Production Overview",
      widgets: [
        {
          type: "trace_count",
          config: { projectId: "prod" },
        },
        {
          type: "cost_chart",
          config: { projectIds: ["prod"] },
        },
      ],
    },
    {
      title: "All Projects Comparison",
      widgets: [
        {
          type: "cost_comparison",
          config: { projectIds: ["prod", "staging", "dev"] },
        },
      ],
    },
    {
      title: "Experiments (No Project Filter)",
      widgets: [
        {
          type: "experiment_leaderboard",
          config: {}, // Experiments are workspace-scoped
        },
      ],
    },
  ];
}
```

---

---

_For implementation timeline and high-level scope, see `dashboard-mvp-summary.md`_
