# Opik Dashboard Widgets - Implementation Plan

**Document Version:** 1.0  
**Date:** November 21, 2025  
**Status:** Widget Implementation Tasks

> **📋 Quick Review:** For a concise, non-technical summary, see [dashboard-mvp-summary.md](./dashboard-mvp-summary.md)

---

## Table of Contents

1. [Experiment Widgets](#1-experiment-widgets)
2. [Cost Widgets](#2-cost-widgets)

---

## 1. Experiment Widgets (3 Widgets)

### 1.1 Experiment Feedback Scores Chart

**Existing Component:**

- `FeedbackScoresChartsWrapper` from `@/components/pages-shared/experiments`
- Uses `useExperimentsFeedbackScoresNames` hook

**API:**

```
GET /v1/private/datasets/{datasetId}/experiments/{experimentId}/feedback-scores
```

**What Needs to Be Built:**

- Create widget component wrapping `FeedbackScoresChartsWrapper`
- Create editor component with:
  - Dataset selector
  - Experiment selector (or multi-select)
  - Widget title/subtitle fields
- Register widget type in `widgetRegistry.tsx`
- Add widget schema validation
- Integrate with widget config dialog

**Implementation:** Wrap existing component + configuration UI

---

### 1.2 Experiment Radar Chart

**Existing Component:**

- `ExperimentsRadarChart` from `@/components/pages-shared/experiments`
- Uses `useCompareExperimentsChartsData` hook

**What Needs to Be Built:**

- Create widget component wrapping `ExperimentsRadarChart`
- Create editor component with:
  - Dataset selector
  - Multi-experiment selector
  - Widget title/subtitle fields
- Register widget type in `widgetRegistry.tsx`
- Add widget schema validation

**Implementation:** Wrap existing component + configuration UI

---

### 1.3 Experiment Bar Chart

**Existing Component:**

- `ExperimentsBarChart` from `@/components/pages-shared/experiments`
- Uses `useCompareExperimentsChartsData` hook

**What Needs to Be Built:**

- Create widget component wrapping `ExperimentsBarChart`
- Create editor component with:
  - Dataset selector
  - Multi-experiment selector
  - Widget title/subtitle fields
- Register widget type in `widgetRegistry.tsx`
- Add widget schema validation

**Implementation:** Wrap existing component + configuration UI

---

## 2. Cost Widgets (2 Widgets)

### 2.1 Cost Summary Card

**API:** `POST /v1/private/workspaces/costs/summaries`

**Request:**

```json
{
  "project_ids": ["uuid1", "uuid2"],
  "interval_start": "2025-01-01T00:00:00Z",
  "interval_end": "2025-01-31T23:59:59Z"
}
```

**Response:**

```json
{
  "results": [
    {
      "name": "cost",
      "current": 1234.56,
      "previous": 987.65
    }
  ]
}
```

**What Needs to Be Built:**

- Widget component displaying:
  - Current period cost
  - Previous period cost
  - Percentage change with trend indicator (up/down)
- Editor component with:
  - Multi-project selector (ProjectsSelectBox with multi-select)
  - Date range configuration
  - Widget title/subtitle fields
- Register widget type in `widgetRegistry.tsx`
- Add widget schema validation

**Implementation:** New widget component + cost calculation logic

---

### 2.2 Cost Trend Chart

**API:** `POST /v1/private/workspaces/costs`

**Request:**

```json
{
  "project_ids": ["uuid1", "uuid2"],
  "interval_start": "2025-01-01T00:00:00Z",
  "interval_end": "2025-01-31T23:59:59Z"
}
```

**Response:**

```json
{
  "results": [
    {
      "project_id": "uuid1",
      "name": "cost",
      "data": [
        { "time": "2025-01-01T00:00:00Z", "value": 123.45 },
        { "time": "2025-01-02T00:00:00Z", "value": 145.67 }
      ]
    }
  ]
}
```

**What Needs to Be Built:**

- Widget component displaying time-series cost data
- Reuse existing chart components (similar to ProjectMetricsWidget)
- Editor component with:
  - Multi-project selector
  - Chart type selector (line/bar)
  - Date range configuration
  - Widget title/subtitle fields
- Register widget type in `widgetRegistry.tsx`
- Add widget schema validation

**Implementation:** New widget component reusing chart infrastructure

---

**Note:** This document focuses only on widget implementation tasks. For dashboard infrastructure, backend API details, and architecture, see other documentation files.
