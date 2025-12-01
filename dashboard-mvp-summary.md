# Opik Dashboard Widgets - MVP Summary

**Document Version:** 1.0  
**Date:** November 21, 2025  
**Status:** Ready for Implementation

> **Quick Review Document** - For technical details, see [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md)

---

## 1. MVP Scope

### What We're Building

Custom dashboards for Opik workspace with drag-drop widgets showing metrics, charts, and data tables.

**Key Features:**

- ✅ Create/edit/delete/clone dashboards (simple table view, like Projects page)
- ✅ Drag-drop sections and widgets
- ✅ Widget configuration dialog with live preview (built in Phase 1, reused for all widgets)
- ✅ 16 widget types (all use existing backend APIs or components)
- ✅ Global date range filter with per-widget override
- ✅ Per-widget project filtering and customization
- ✅ Chart type selector (line/bar/area) built into each chart widget
- ✅ Backend persistence (existing API, zero backend work)

**What's NOT in MVP:**

- ❌ Dashboard templates (predefined dashboards)
- ❌ Multi-project global filter (per-widget only)
- ❌ Advanced cost breakdowns (by model/provider)
- ❌ Dashboard connections to traces/experiments pages

---

## 2. Widget Types (16 Total)

### Stats Widgets (1) - ✅ RENAMED

Single **Project Stats Card** widget (renamed from Stat Card) that displays live metrics from three stats endpoints.

#### Available Stats Endpoints

**1. Projects Stats**

- **Total cost** - Cumulative cost across all traces
- **Trace count** - Number of traces in project
- **Feedback scores** - Any configured score (accuracy, precision, etc.)
- **Duration percentiles** - p50, p90, p99 latency
- **Token usage** - Prompt, completion, and total tokens
- **Guardrails failed count** - Safety violations
- **Error count** - Failed traces

**2. Traces Stats**

- **Trace count** - Total number of traces
- **Total cost** - Sum of all trace costs
- **Feedback scores** - Average scores across traces
- **Duration percentiles** - Performance distribution
- **Token usage** - Aggregated token consumption

**3. Spans Stats**

- **Span count** - Total number of spans
- **LLM token usage** - Prompt/completion/total at span level
- **Error rate** - Percentage of failed spans
- **Duration percentiles** - Span-level performance

### Text Widgets (1) - ✅ COMPLETED

| Widget        | Description         | Data Source   | Status  |
| ------------- | ------------------- | ------------- | ------- |
| Text/Markdown | Custom text content | Frontend only | ✅ Done |

**Implementation Status:** TextMarkdownWidget fully implemented with CodeMirror editor

**Use Cases:**

- Dashboard headers and descriptions
- Documentation links
- Custom notes and instructions
- Formatted content with Markdown

### Chart Widgets - Project Metrics (9) - ✅ COMPLETED

| Widget                 | Metric Type               | Charts   | Status  |
| ---------------------- | ------------------------- | -------- | ------- |
| Feedback Scores        | `FEEDBACK_SCORES`         | Line/Bar | ✅ Done |
| Trace Count            | `TRACE_COUNT`             | Line/Bar | ✅ Done |
| Token Usage            | `TOKEN_USAGE`             | Line/Bar | ✅ Done |
| Duration               | `DURATION`                | Line/Bar | ✅ Done |
| Cost                   | `COST`                    | Line/Bar | ✅ Done |
| Guardrails Failed      | `GUARDRAILS_FAILED_COUNT` | Line/Bar | ✅ Done |
| Thread Count           | `THREAD_COUNT`            | Line/Bar | ✅ Done |
| Thread Duration        | `THREAD_DURATION`         | Line/Bar | ✅ Done |
| Thread Feedback Scores | `THREAD_FEEDBACK_SCORES`  | Line/Bar | ✅ Done |

**Implementation Status:** ProjectMetricsWidget fully implemented with:

- Full filtering support (trace/thread filters)
- Chart type selector (line/bar)
- Project selector with fallback to dashboard project
- Global/local date range toggle
- Widget config dialog with live preview

**All use existing backend API**

### Chart Widgets - Workspace Cost (2)

| Widget           | Description                | Notes                   |
| ---------------- | -------------------------- | ----------------------- |
| Cost Summary     | Current vs previous period | Uses workspace cost API |
| Cost Time Series | Daily cost data            | Uses workspace cost API |

**Supports multi-project filtering** (workspace-level API)

### Experiment Widgets (3)

| Widget                     | Description                           | Implementation    |
| -------------------------- | ------------------------------------- | ----------------- |
| Experiment Feedback Scores | Line charts for feedback score trends | Reuse existing UI |
| Experiment Radar Chart     | Compare experiments (radar)           | Reuse existing UI |
| Experiment Bar Chart       | Feedback score distribution (bar)     | Reuse existing UI |

**Note:**

- Experiments are workspace-scoped (no project filter needed)
- These charts **already exist** on Experiments page - just need to be wrapped as widgets
- Data comes from experiment `feedback_scores` field (no new API needed)

---

## 3. Filtering Strategy

### Global Filter (1)

- **Date Range** - applies to all widgets by default (reuse existing DateRangeSelect)
  - Presets: Past 24 hours, 3 days, 7 days, 30 days, 60 days, All time, Custom
  - Each widget can override

### Per-Widget Filters

| Filter Type         | Applies To                     | Backend Support       |
| ------------------- | ------------------------------ | --------------------- |
| Project (single)    | Traces, Spans, Project Metrics | ✅ Most endpoints     |
| Projects (multiple) | Cost widgets only              | ✅ Workspace cost API |
| Trace filters       | Trace-based widgets            | ✅ Query params       |
| Thread filters      | Thread-based widgets           | ✅ Query params       |
| None                | Experiments, Workspace stats   | ✅ N/A                |

**Why No Global Project Filter?**

- Experiments are workspace-scoped (not tied to projects)
- Backend APIs inconsistent (some support multi-project, most don't)
- More flexible (different widgets show different projects)

---

## 4. Implementation Plan

### Timeline: 20 Days (1 Developer with AI)

| Approach                  | Dev-Days | Calendar Time | Notes                                                     |
| ------------------------- | -------- | ------------- | --------------------------------------------------------- |
| **With AI Assistance** ✅ | **14**   | **~3 weeks**  | Recommended - AI generates boilerplate, components, types |
| Manual coding             | 24       | ~5 weeks      | Traditional approach (not recommended)                    |

### Delivery Phases (Incremental User Value)

| Phase       | What Users Get                                   | Days        | Deliverable?                           | Components                        |
| ----------- | ------------------------------------------------ | ----------- | -------------------------------------- | --------------------------------- |
| **Phase 0** | **Infrastructure**                               | **3**       | ❌ **Not deliverable alone**           | Setup only, no user value         |
|             | - Dashboard list page with CRUD + clone          | 1           | Includes API integration               |
|             | - React Grid Layout + sections                   | 1.5         | -                                      |
|             | - Widget framework (types, registry)             | 0.5         | -                                      |
| **Phase 1** | **Widget Config Dialog + First 3 Widgets**       | **4.5**     | ✅ **DEMO READY**                      | Config UI + Basic widgets         |
|             | 0. Widget config dialog with live preview        | 1.5         | Reusable settings panel + preview pane |
|             | 1. Stat Card (with filters, date range)          | 0.75        | Full config: metric, project, filters  |
|             | 2. Text/Markdown (CodeMirror + viewer)           | 0.5         | Editor + preview (reuse existing)      |
|             | 3. Cost Summary (with project filter)            | 1.25        | Full config: projects, date range      |
|             | - Testing & bug fixes                            | 0.5         | Test all Phase 1 widgets               |
| **Phase 2** | **All 9 Metric Charts (Same Component)**         | **4.25**    | ✅ **PRODUCTION READY**                | All project metrics with settings |
|             | 4. Feedback Scores chart (base + 3 chart types)  | 1.5         | Reusable component (line/bar/area)     |
|             | 5-12. Remaining 8 charts (config only)           | 2           | Schema + testing (0.25 each)           |
|             | _Same component, different metric configs_       |             | Copy-paste efficiency                  |
|             | - Testing & bug fixes                            | 0.75        | Test all chart variations              |
| **Phase 3** | **Experiment & Cost Widgets (Fully Configured)** | **2.25**    | ✅ **FEATURE COMPLETE**                | Experiments + cost with settings  |
|             | 13. Experiment Feedback Scores (reuse existing)  | 0.75        | Wrap existing component + config       |
|             | 14. Experiment Radar Chart (reuse existing)      | 0.5         | Wrap existing component + config       |
|             | 15. Experiment Bar Chart (reuse existing)        | 0.5         | Wrap existing component + config       |
|             | 16. Cost Trend chart                             | 0.5         | Config: projects, chart type, date     |
| **Phase 4** | **Documentation**                                | **0.5**     | ✅ **MVP LAUNCH**                      | Ready for production              |
|             | - Documentation                                  | 0.5         | User guides, widget docs               |
|             | **TOTAL:**                                       | **14 days** |                                        | **~3 weeks**                      |

### Delivery Strategy

**Week 1: Phase 0-1** (Foundation + Config Dialog + First Widgets)

- **Output:** Dashboard infrastructure + reusable widget config dialog + 3 fully functional widgets
- **User Value:** ⭐ **Can create and configure basic dashboards**
- **Milestone:** Demo widget configuration with live preview
- **What Works:** Users can add widgets, configure settings, see live preview, filter data
- **Testing:** Tested during phase, ready to demo

**Week 2: Phase 2** (All Metric Charts)

- **Output:** All 9 project metric charts, each with full configuration (chart type, filters, date range)
- **User Value:** ⭐⭐ **Complete metrics coverage with customization**
- **Milestone:** Production-ready metric dashboards
- **What Works:** Line/bar/area charts, per-widget filtering, project selection
- **Testing:** All chart types and configs tested

**Week 3: Phase 3** (Experiments + Cost)

- **Output:** 3 experiment widgets + cost trend, all fully configurable
- **User Value:** ⭐⭐⭐ **All 16 widgets complete and production-ready**
- **Milestone:** Feature complete and tested
- **What Works:** Full widget catalog, all configs, all chart types, optimized performance
- **Testing:** Integration tests complete

**Week 4: Phase 4** (Documentation)

- **Output:** Documentation and user guides
- **User Value:** ⭐⭐⭐⭐ **Production-ready MVP with documentation**
- **Milestone:** General availability
- **What Works:** Complete, tested, documented dashboard system

**Why Incremental Delivery?**

- ✅ **Early Feedback:** Demo after 2 weeks, adjust based on user input
- ✅ **Risk Mitigation:** Can stop/pivot if priorities change
- ✅ **User Validation:** Ensure features meet actual needs
- ✅ **Iterative Improvement:** Learn from each phase

**What Can Be Demoed at Each Phase?**

| After Week | Phase Complete | Demo Content                                                                                  |
| ---------- | -------------- | --------------------------------------------------------------------------------------------- |
| Week 1     | Phase 0-1      | "Widget config dialog with live preview + 3 fully functional widgets (with filters & config)" |
| Week 2     | Phase 2        | "All 9 metric charts with chart type selector, filters, and full customization"               |
| Week 3     | Phase 3        | "All 16 widgets complete - fully configurable with all settings"                              |
| Week 4     | Phase 4        | "Polished, tested, production-ready MVP with documentation"                                   |

**AI Assistance Benefits:**

- Generates boilerplate code (CRUD, forms, types)
- Creates widget templates and configurations
- Scaffolds tests
- ~40% speed improvement over manual coding

**Manual Work:**

- Architecture decisions
- Complex interactions (drag-drop, live preview)
- Performance optimization
- Edge cases and integration

---

## 5. Out of MVP Scope

- Dashboard templates (predefined)
- Multi-project global filter
- Advanced cost breakdowns (by model/provider)
- Dashboard sharing/permissions
- Custom calculated metrics
- Dashboard connections to traces/experiments pages

---

## 6. Decision Log

| Decision                                       | Rationale                                                                    |
| ---------------------------------------------- | ---------------------------------------------------------------------------- |
| **Incremental delivery (4 phases)**            | Get user feedback early; reduce risk; validate each step                     |
| **1 developer with AI**                        | AI speeds up by ~40%; realistic 3 week timeline (~14 dev-days)               |
| **Widget config dialog built first (Phase 1)** | Reusable for all 16 widgets; provides consistency; includes live preview     |
| **Per-widget config built into each widget**   | Widget not complete without its settings; users need immediate functionality |
| **Every phase deliverable (except Phase 0)**   | Incremental user value; can demo working features at each milestone          |
| **Clone dashboard in MVP**                     | Simple feature; useful for users                                             |
| No global project filter                       | Experiments are workspace-scoped; per-widget filtering more flexible         |
| Simple table view for list                     | Match existing pages pattern; faster to build                                |
| No dashboard templates in MVP                  | Users can create from scratch or clone existing                              |
| 16 widgets (3 reuse existing)                  | All use existing APIs/components; covers core use cases                      |
| Per-widget project filtering                   | More flexible; works with all widget types                                   |
| Chart type selector per widget                 | Different visualizations for same data                                       |
| Auto-save (no manual save)                     | Better UX; matches modern dashboard tools                                    |
| Demo after Week 1 (Phase 1)                    | Early stakeholder feedback with working features                             |

---

## 7. Next Steps

1. **Confirm MVP scope** with stakeholders
2. **Assign developer** (1 developer with AI tools)
3. **Review backend API** ✅ (verified - zero changes needed)
4. **Create Jira tickets** for each phase:
   - Phase 0: Infrastructure (5 days)
   - Phase 1: Widget config dialog + 3 widgets (4 days) → **Demo milestone**
   - Phase 2: 9 metric charts (2.75 days) → **Production milestone**
   - Phase 3: Experiments + cost (2.25 days) → **Feature complete**
   - Phase 4: Testing + docs (2.75 days) → **Launch milestone**
5. **Start Phase 0** with weekly check-ins
6. **Plan demo after Week 1** - Show widget configuration with live preview

---

**For Technical Details:**  
See [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md) (2000+ lines with API specs, code examples, data structures, etc.)

**For Phase 2+ Features:**  
See [dashboard-widgets-next-steps.md](./dashboard-widgets-next-steps.md) (advanced widgets, backend enhancements, future roadmap)
