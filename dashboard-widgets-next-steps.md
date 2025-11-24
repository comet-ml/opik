# Opik Dashboard Widgets - Next Steps (Phase 2+)

**Document Version:** 2.0  
**Date:** November 21, 2025  
**Status:** Future Enhancements - Table Widgets

> **📋 Quick Review:** For MVP scope, see [dashboard-mvp-summary.md](./dashboard-mvp-summary.md)  
> **📖 Technical Details:** See [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md)  
> **🚀 This Document:** Table widgets for Phase 2 - ranking, sorting, and list views

---

## Overview

This document outlines **Table Widgets** for Phase 2 implementation. These widgets were removed from MVP to reduce scope but provide significant value for users who need to:

- **Rank entities** by metrics (best/worst N experiments, traces, projects)
- **Sort and filter** data dynamically
- **Compare** multiple entities in a table view

**Key Benefit:** All table widgets use **existing backend APIs** - zero backend work required!

---

## Table of Contents

1. [Experiments Table Widgets](#1-experiments-table-widgets)
2. [Traces Table Widgets](#2-traces-table-widgets)
3. [Projects Table Widgets](#3-projects-table-widgets)
4. [Datasets Table Widgets](#4-datasets-table-widgets)
5. [Implementation Summary](#5-implementation-summary)

---

## 1. Experiments Table Widgets

### 1.1 Experiments Ranking Table ⭐⭐⭐⭐⭐

**Description:** Rank experiments by any metric - show best/worst performers

**Backend API:** ✅ `GET /v1/private/experiments` (existing)

**Available Sort Options:**

- `name` - Sort alphabetically
- `created_at` - Sort by creation date
- `last_updated_at` - Sort by last update
- `feedback_scores.{scoreName}` - Sort by any feedback score
- Any custom metric defined in experiment

**Use Cases:**

#### Best N Experiments by Score

Show top 10 experiments with highest accuracy:

```typescript
GET /experiments?
  dataset_id={datasetId}
  &sorting=feedback_scores.accuracy,desc
  &size=10
```

**Widget Display:**

```
┌─────────────────────────────────────────────────────┐
│ Top 10 Experiments by Accuracy                      │
├─────────────────────────────────────────────────────┤
│ Rank | Experiment Name     | Accuracy | Precision    │
│ 1    | GPT-4 Prompt v3     | 0.98     | 0.96         │
│ 2    | Claude Enhanced     | 0.96     | 0.95         │
│ 3    | GPT-4 Baseline      | 0.94     | 0.93         │
│ 4    | Fine-tuned Model    | 0.92     | 0.90         │
│ 5    | Few-shot GPT-3.5    | 0.89     | 0.88         │
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

#### Worst N Experiments (Find Problems)

Show bottom 5 experiments with lowest scores:

```typescript
GET /experiments?
  dataset_id={datasetId}
  &sorting=feedback_scores.accuracy,asc
  &size=5
```

**Use Case:** Identify failing experiments that need attention

#### Recently Updated Experiments

```typescript
GET /experiments?
  dataset_id={datasetId}
  &sorting=last_updated_at,desc
  &size=10
```

**Use Case:** Track team activity, see what's being worked on

---

### 1.2 Experiment Comparison Table ⭐⭐⭐⭐

**Description:** Compare multiple experiments side-by-side across all metrics

**Backend API:** ✅ `GET /v1/private/experiments` (existing)

**Widget Configuration:**

```typescript
{
  type: "experiment_comparison_table",
  config: {
    datasetId: "...",
    experimentIds: ["exp-1", "exp-2", "exp-3"], // User selects which to compare
    metrics: ["accuracy", "precision", "cost", "latency"]
  }
}
```

**Widget Display:**

```
┌───────────────────────────────────────────────────────────────┐
│ Experiment Comparison                                         │
├───────────────────────────────────────────────────────────────┤
│ Experiment      | Accuracy | Precision | Cost   | Latency    │
│ GPT-4 Prompt v3 | 0.98     | 0.96      | $12.45 | 1.2s       │
│ Claude Enhanced | 0.96     | 0.95      | $8.76  | 0.9s  ✅   │
│ GPT-4 Baseline  | 0.94     | 0.93      | $15.23 | 1.5s       │
└───────────────────────────────────────────────────────────────┘
```

**Use Case:** A/B testing, choose best experiment for production

---

## 2. Traces Table Widgets

### 2.1 Top/Bottom Traces Ranking ⭐⭐⭐⭐⭐

**Description:** Rank traces by feedback scores, cost, duration, or any metric

**Backend API:** ✅ `GET /v1/private/traces` (existing)

**Available Sort Options:**

- `feedback_scores.{scoreName}` - Sort by feedback score
- `total_estimated_cost` - Sort by cost
- `duration` - Sort by duration
- `start_time` - Sort by time
- `end_time` - Sort by completion time

**Use Cases:**

#### Top 10 Traces by Feedback Score

```typescript
GET /traces?
  project_id={projectId}
  &sorting=feedback_scores.accuracy,desc
  &size=10
```

**Widget Display:**

```
┌─────────────────────────────────────────────────────┐
│ Top 10 Traces by Accuracy                           │
├─────────────────────────────────────────────────────┤
│ Trace ID       | Name           | Score | Cost      │
│ trace-abc123   | User Query 1   | 0.98  | $0.02     │
│ trace-def456   | User Query 2   | 0.97  | $0.03     │
│ trace-ghi789   | Complex Task   | 0.96  | $0.05     │
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

**Use Case:** Find best-performing traces, analyze what makes them successful

#### Most Expensive Traces

```typescript
GET /traces?
  project_id={projectId}
  &sorting=total_estimated_cost,desc
  &size=20
```

**Widget Display:**

```
┌─────────────────────────────────────────────────────┐
│ Most Expensive Traces (Top 20)                      │
├─────────────────────────────────────────────────────┤
│ Trace ID       | Name           | Cost  | Tokens    │
│ trace-xyz999   | Long Analysis  | $2.45 | 125K      │
│ trace-abc888   | Multi-step Gen | $1.89 | 98K       │
│ trace-def777   | Complex Query  | $1.34 | 67K       │
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

**Use Case:** Identify cost outliers, optimize expensive operations

#### Slowest Traces

```typescript
GET /traces?
  project_id={projectId}
  &sorting=duration,desc
  &size=10
```

**Use Case:** Find performance bottlenecks

#### Worst Performing Traces (Lowest Scores)

```typescript
GET /traces?
  project_id={projectId}
  &sorting=feedback_scores.accuracy,asc
  &size=10
```

**Use Case:** Identify problematic traces, find patterns in failures

---

### 2.2 Traces List with Multiple Metrics ⭐⭐⭐⭐

**Description:** Configurable table showing traces with any columns user wants

**Widget Configuration:**

```typescript
{
  type: "traces_table",
  config: {
    projectId: "...",
    sorting: "total_estimated_cost,desc", // User-selected
    columns: ["name", "feedback_scores.accuracy", "cost", "duration", "tokens"],
    filters: {
      tags: ["production"],
      minCost: 0.1
    },
    limit: 25
  }
}
```

**Widget Display:**

```
┌─────────────────────────────────────────────────────────────────┐
│ Production Traces (Cost > $0.10)                  Sort: Cost ▼  │
├─────────────────────────────────────────────────────────────────┤
│ Name         | Accuracy | Cost  | Duration | Tokens | Status   │
│ Analysis v2  | 0.92     | $1.45 | 3.2s     | 67K    | ✅       │
│ Query Gen    | 0.89     | $1.23 | 2.8s     | 54K    | ✅       │
│ Complex Task | 0.78     | $0.98 | 4.1s     | 43K    | ⚠️       │
│ ...                                                             │
└─────────────────────────────────────────────────────────────────┘
```

**Use Case:** Custom monitoring, filter and sort by any criteria

---

## 3. Projects Table Widgets

### 3.1 Projects Summary & Ranking ⭐⭐⭐⭐

**Description:** Overview of all projects with ability to sort by any metric

**Backend API:** ✅ `GET /v1/private/projects/stats` (existing)

**Available Sort Options:**

- `name` - Alphabetical
- `trace_count` - Number of traces
- `total_estimated_cost` - Total cost
- `last_updated_trace_at` - Most recent activity
- `feedback_scores.{scoreName}` - Any feedback score
- `duration.p99` - Performance metrics

**Use Cases:**

#### Most Active Projects (by Trace Count)

```typescript
GET /projects/stats?
  sorting=trace_count,desc
  &size=10
```

**Widget Display:**

```
┌─────────────────────────────────────────────────────┐
│ Most Active Projects                                 │
├─────────────────────────────────────────────────────┤
│ Project      | Traces | Cost     | Avg Duration     │
│ Production   | 12.5K  | $456.78  | 1.2s             │
│ Staging      | 3.4K   | $123.45  | 0.9s             │
│ Development  | 890    | $34.56   | 0.7s             │
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

#### Most Expensive Projects

```typescript
GET /projects/stats?
  sorting=total_estimated_cost,desc
  &size=10
```

**Use Case:** Identify cost leaders, allocate budget

#### Best Performing Projects (by Feedback Score)

```typescript
GET /projects/stats?
  sorting=feedback_scores.accuracy,desc
  &size=10
```

**Use Case:** Benchmark projects, identify best practices

#### Recently Updated Projects

```typescript
GET /projects/stats?
  sorting=last_updated_trace_at,desc
  &size=10
```

**Use Case:** See what teams are working on

---

### 3.2 Project Comparison Table ⭐⭐⭐

**Description:** Compare specific projects side-by-side

**Widget Configuration:**

```typescript
{
  type: "project_comparison_table",
  config: {
    projectIds: ["proj-1", "proj-2", "proj-3"],
    metrics: ["trace_count", "cost", "duration.p99", "feedback_scores.accuracy"]
  }
}
```

**Widget Display:**

```
┌───────────────────────────────────────────────────────────────┐
│ Project Comparison                                            │
├───────────────────────────────────────────────────────────────┤
│ Project     | Traces | Cost     | P99 Dur | Accuracy | Winner │
│ Production  | 12.5K  | $456.78  | 2.3s    | 0.94     | ⚡ 2/4  │
│ Staging     | 3.4K   | $123.45  | 1.8s ✅ | 0.92     | ✅ 1/4  │
│ Development | 890    | $34.56 ✅ | 2.1s    | 0.96 ✅  | ⚡ 1/4  │
└───────────────────────────────────────────────────────────────┘
```

**Use Case:** Environment comparison, quality control

---

## 4. Datasets Table Widgets

### 4.1 Datasets Overview & Ranking ⭐⭐⭐

**Description:** List all datasets with ability to sort by usage, experiments, activity

**Backend API:** ✅ `GET /v1/private/datasets` (existing)

**Available Sort Options:**

- `name` - Alphabetical
- `created_at` - Creation date
- `last_updated_at` - Recent activity
- `experiment_count` - Number of experiments (if tracked)

**Use Cases:**

#### Most Used Datasets (by Experiments)

**Widget Display:**

```
┌─────────────────────────────────────────────────────┐
│ Most Used Datasets                                   │
├─────────────────────────────────────────────────────┤
│ Dataset Name       | Experiments | Items | Updated  │
│ Production Queries | 45          | 1.2K  | 2d ago   │
│ QA Test Set        | 32          | 500   | 5d ago   │
│ Edge Cases         | 18          | 250   | 1w ago   │
│ ...                                                  │
└─────────────────────────────────────────────────────┘
```

**Use Case:** Find most valuable datasets, identify unused ones

#### Recently Updated Datasets

```typescript
GET /datasets?
  sorting=last_updated_at,desc
  &size=10
```

**Use Case:** Track data quality improvements, see active work

---

## 5. Implementation Summary

### All Table Widgets Requirements

**Backend:** ✅ **ZERO work required** - all APIs exist with sorting support

**Frontend Work:**

| Widget Type              | Description                   | Days |
| ------------------------ | ----------------------------- | ---- |
| Experiments Ranking      | Sort by metrics, top/bottom N | 0.5  |
| Experiment Comparison    | Side-by-side comparison       | 0.5  |
| Traces Ranking           | Sort traces by any metric     | 0.5  |
| Traces Custom Table      | Configurable columns/filters  | 0.5  |
| Projects Summary         | Sort projects by metrics      | 0.5  |
| Project Comparison       | Compare multiple projects     | 0.5  |
| Datasets Overview        | List and rank datasets        | 0.5  |
| **Total:**               |                               | **3.5 days** |

### Shared Components

All table widgets share:

- **Table component** with sorting (click column headers)
- **Pagination** (page through results)
- **Column configuration** (show/hide columns)
- **Export to CSV** (optional)
- **Click row to navigate** (open trace/experiment/project detail)

**Reusable effort:** Build table framework once (~1 day), then each widget is ~0.5 day

---

## Priority Ranking for Phase 2

### 🔥 **Highest Priority** (Immediate User Value)

1. **Traces Ranking** - Top/worst traces by scores → Find issues fast
2. **Experiments Ranking** - Best/worst experiments → A/B testing
3. **Most Expensive Traces** - Cost outliers → Reduce spend

### ⚡ **High Priority** (Common Use Cases)

4. **Projects Summary** - Monitor all projects → Overview dashboard
5. **Experiment Comparison** - Side-by-side → Choose production model
6. **Slowest Traces** - Performance bottlenecks → Optimize latency

### ✅ **Medium Priority** (Nice to Have)

7. **Project Comparison** - Environment benchmarks
8. **Datasets Overview** - Data management
9. **Traces Custom Table** - Power users with specific needs

---

## Usage Scenarios

### Scenario 1: Find Best Model for Production

1. Add **Experiments Ranking Table** widget
2. Sort by `feedback_scores.accuracy,desc`
3. Compare top 3 with **Experiment Comparison** widget
4. Consider cost/latency trade-offs
5. Deploy winner to production

---

### Scenario 2: Reduce Costs

1. Add **Most Expensive Traces** widget
2. Sort by `total_estimated_cost,desc`
3. Analyze top 20 traces
4. Identify patterns (model, prompt length, etc.)
5. Optimize expensive operations

---

### Scenario 3: Debug Quality Issues

1. Add **Worst Traces** widget (sort by score ascending)
2. Filter by date range (last 7 days)
3. Click traces to inspect details
4. Find common failure patterns
5. Fix root cause

---

### Scenario 4: Monitor Team Activity

1. Add **Recently Updated Projects** widget
2. Add **Recently Updated Experiments** widget
3. Add **Recent Traces** widget
4. See what's being worked on across teams

---

## Next Steps

1. **Prioritize** which table widgets to build first
2. **Build table framework** (1 day) - shared by all widgets
3. **Implement top 3 priority widgets** (1.5 days)
4. **User testing** - gather feedback
5. **Iterate** - add more widgets based on demand

**Total Phase 2 Effort:** ~3.5 days for all table widgets  
**Timeline:** 1 week for 1 developer with AI

---

**For MVP Features:**  
See [dashboard-mvp-summary.md](./dashboard-mvp-summary.md) - 16 widgets, 20 days

**For Technical Details:**  
See [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md) - Full API specs
