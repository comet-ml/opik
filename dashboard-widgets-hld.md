# Opik Dashboard Widgets - High-Level Design

**Document Version:** 1.0  
**Date:** November 24, 2025  
**Status:** Ready for Review  
**Feature Owner:** Andrii Dudar

---

## 📋 Quick Links

- **Product Requirements:** [Expanding Opik Dashboarding Capabilities](https://www.notion.so/cometml/Expanding-Opik-Dashboarding-Capabilities-28e7124010a380069576e67f947ce5e7)
- **MVP Scope:** [dashboard-mvp-summary.md](./dashboard-mvp-summary.md)
- **Technical Details:** [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md)
- **Possible Next Steps:** [dashboard-widgets-next-steps.md](./dashboard-widgets-next-steps.md)
- **Grid Layout Research:** [dashboard-grid-layout-investigation.md](./dashboard-grid-layout-investigation.md)

---

## 1. Product Requirements Summary

### Business Need
Customers (Binance, Autodesk, Zoox, Salesforce, Swiggy) need customizable dashboards to visualize:
- Operational monitoring (latency, cost, errors)
- Quality metrics (LLM output quality, user satisfaction)
- Experiment comparisons (A/B testing, model evaluation)

### MVP Scope (3 weeks, 1 developer)
- ✅ Create/edit/delete/clone dashboards
- ✅ 16 widget types (all use existing APIs)
- ✅ Drag-drop sections and widgets
- ✅ Global + per-widget filtering
- ✅ Backend persistence (existing API, zero BE work)

### Out of MVP
- ❌ Dashboard templates
- ❌ Multi-project global filter
- ❌ Dashboard sharing/permissions
- ❌ Advanced cost breakdowns

---

## 2. Functional Requirements (Customer → Technical)

| Customer Pain Point | Source | Functional Requirement | Technical Solution | Phase |
|-------------------|--------|----------------------|-------------------|-------|
| "Filter out system_error=1 to see true model performance" | Zoox | Per-widget metadata filtering | FilterBuilder UI in widget config | Phase 1 |
| "Monthly views instead of daily only" | Autodesk | Custom date range selection | DateRangeSelect with custom picker | Phase 0 |
| "Cost breakdown by user/project/account" | Autodesk | Multi-project cost visualization | Cost widgets with project multi-select | Phase 3 |
| "Custom dashboard for each project" | Binance | Multiple dashboards per workspace | Dashboard CRUD with list page | Phase 0 |
| "Flexible metric visualization" | Zoox | Multiple chart types | Line/bar/area selector per widget | Phase 2 |
| "Compare experiments side-by-side" | Data Scientists | Experiment comparison widgets | Radar + bar + line chart widgets | Phase 3 |
| "Intuitive drag-drop interface" | Snapchat | Grid-based layout | react-grid-layout + collapsible sections | Phase 0 |
| "Share dashboards with team" | Product req | Dashboard cloning | Clone dashboard with all config | Phase 0 |

---

## 3. High-Level Solution Design

### Architecture Overview

```
User Browser (React 18.3.1)
    ↓
Dashboard UI Components
    ├── DashboardsListPage (CRUD operations)
    └── DashboardPage
        ├── GlobalFilters (date range)
        ├── Sections (collapsible)
        └── Widgets (16 types)
    ↓
Existing Backend API (Dropwizard)
    ├── GET/POST/PATCH/DELETE /v1/private/dashboards
    ├── POST /v1/private/projects/{id}/metrics
    └── POST /v1/private/workspaces/costs
    ↓
MySQL (dashboard config as opaque JSON) + ClickHouse (metrics data)
```

### Key Components

**1. Dashboard List Page** (Phase 0)
- Simple table view (matches Projects/Traces pattern)
- Actions: Create, Clone, Delete
- Search and sort by name/date

**2. Dashboard Detail Page** (Phase 0)
- Header with name, save status, actions
- Global date range filter (applies to all widgets)
- Sections with drag-drop grid layout
- Auto-save (debounced 2s)

**3. Widget Configuration Dialog** (Phase 1)
- **Two-panel design:** Settings form (left) + Live preview (right)
- Reusable for all 16 widget types
- Fetches real data during configuration
- Schema-driven form generation

**4. Widget Types** (Phases 1-3)
- Project Stats Cards (1 type) - ✅ Renamed, type definitions updated
- Project Metric Charts (9 types) - ✅ Fully implemented with ProjectMetricsWidget
- Experiment Charts (3 types) - ⏳ TODO
- Cost Widgets (2 types) - ⏳ TODO  
- Text/Markdown (1 type) - ✅ Fully implemented with TextMarkdownWidget

### Data Model

```typescript
interface Dashboard {
  id: string;
  name: string;
  description?: string;
  workspace_id: string;
  config: {
    schemaVersion: number;  // FE-owned versioning
    globalDateRange: { preset: "past24hours" | "past7days" | ... };
    sections: Section[];
  };
  created_at: string;
  last_updated_at: string;
}

interface Section {
  id: string;
  title: string;
  expanded: boolean;
  widgets: Widget[];
  layout: GridLayout[];
}

interface Widget {
  id: string;
  type: string;
  config: {
    name: string;
    projectId?: string;        // Per-widget project filter
    dateRange?: DateRange;     // Override global date range
    chartType?: "line" | "bar" | "area";
    filters?: Filter[];        // Trace/thread filters
    // ... widget-specific config
  };
}
```

---

## 4. Key Decisions & Trade-offs

| Decision | Options Considered | Choice | Rationale | Trade-offs |
|----------|-------------------|--------|-----------|------------|
| **Grid layout library** | 1. react-grid-layout<br>2. Gridstack.js<br>3. dnd-kit + custom | **react-grid-layout** | React-first, 20k+ stars, mature, already in dependencies | ❌ No multi-grid drag support (acceptable for MVP) |
| **Widget config UX** | 1. Modal with preview<br>2. Inline editor<br>3. Multi-step wizard | **Modal with live preview** | Immediate feedback, catches errors early, reusable for all 16 widgets | ❌ Requires debounced API calls (300ms) |
| **Date filtering** | 1. Global only<br>2. Per-widget only<br>3. Both | **Global + per-widget override** | Flexible without complexity, matches user mental model | ❌ More config surface area per widget |
| **Project filtering** | 1. Global multi-project<br>2. Per-widget only<br>3. Section-level | **Per-widget only** | Experiments are workspace-scoped, different widgets show different projects | ❌ Can't apply project filter globally (acceptable - more flexible) |
| **Backend API** | 1. New API<br>2. Extend existing<br>3. Use existing as-is | **Use existing API** | Dashboard CRUD already exists, metrics APIs support all needed params | ✅ Zero backend work (MVP goal) |
| **Schema versioning** | 1. Separate DB field<br>2. Inside config JSON | **Inside config JSON** | Zero BE changes, FE owns evolution, unlimited schema changes | ❌ Not queryable at DB level (acceptable - rarely needed) |
| **Text editor** | 1. Plain textarea<br>2. CodeMirror<br>3. Rich text editor | **CodeMirror** | Already in project, Markdown syntax highlighting, good UX | ❌ Slightly heavier than textarea (~50KB) |
| **Chart types** | 1. Line only<br>2. Line + bar<br>3. All types | **Line + bar + area** | Backend data same for all types, Recharts supports all, user flexibility | ❌ More config complexity (acceptable - users need it) |

---

## 5. Testing & Quality Strategy

### Acceptance Criteria (Definition of "Done")

**Phase 1 Complete (Demo Ready):**
- [ ] Dashboard list page loads in < 1s
- [ ] Create/clone/delete dashboards work without errors
- [ ] Widget config dialog shows live preview within 500ms
- [ ] 3 widgets (Stat Card, Text, Cost Summary) render correctly
- [ ] Auto-save triggers within 2s of changes

**Phase 2 Complete (Production Ready):**
- [ ] All 9 metric charts render with line/bar/area types
- [ ] Per-widget project filtering works correctly
- [ ] Global date range filter applies to all widgets
- [ ] Dashboard with 10 widgets loads in < 2s
- [ ] Zero widget render errors

**Phase 3 Complete (Feature Complete):**
- [ ] All 16 widgets functional with full configuration
- [ ] Experiment widgets display correct data
- [ ] Cost widgets support multi-project selection
- [ ] Error boundaries catch all widget failures
- [ ] Dashboard with 20 widgets loads in < 3s

### Testing Approach

**TBD** - Will be defined during implementation phase.

---

## 6. Rollout & Fallback Plan

### Feature Flag Strategy

**This feature is fully additive:**
- ✅ Adds new dashboard functionality (new routes, new UI)
- ✅ Does NOT modify or delete existing functionality
- ✅ Existing pages (Projects, Traces, Experiments) remain unchanged
- ✅ Zero impact on current users when feature flag is disabled

**Feature Flag:**

```typescript
dashboards_enabled: boolean  // Master switch (controls entire dashboard feature)
```

**When `dashboards_enabled = false`:**
- Dashboard navigation menu hidden
- `/dashboards` routes return 404
- Zero impact on existing functionality

**When `dashboards_enabled = true`:**
- All 16 widget types available immediately
- All phases (0-4) delivered together as single release

### Rollback Procedure

**Safe Rollback (No Risk):**
- Feature is completely isolated - disabling feature flag instantly hides all dashboard functionality
- No database migrations or schema changes
- No modifications to existing APIs or pages

**Triggers:**
- Error rate > 1%
- P95 load time > 5s
- Critical bug reports

**Rollback Steps:**
1. Disable `dashboards_enabled` feature flag (instant, zero risk)
2. Optional: Revert frontend deployment if needed
3. Database: No rollback needed (config is opaque JSON, no schema changes)
4. Notify affected users via in-app message

### Monitoring

**TBD** - Will be defined during implementation phase.

---

## 7. Timeline & Resources

**Effort:** 14 days (with AI assistance)  
**Team:** 1 developer  
**Timeline:** 3 weeks

### Delivery Phases (Incremental User Value)

| Phase       | What Users Get                                   | Days           | Deliverable?                           | Components                        |
| ----------- | ------------------------------------------------ | -------------- | -------------------------------------- | --------------------------------- |
| **Phase 0** | **Infrastructure**                               | **3**          | ❌ **Not deliverable alone**           | Setup only, no user value         |
|             | - Dashboard list page with CRUD + clone          | 1              | Includes API integration               |
|             | - React Grid Layout + sections                   | 1.5            | -                                      |
|             | - Widget framework (types, registry)             | 0.5            | -                                      |
| **Phase 1** | **Widget Config Dialog + First 3 Widgets**       | **4.5**        | ✅ **DEMO READY**                      | Config UI + Basic widgets         |
|             | 0. Widget config dialog with live preview        | 1.5            | Reusable settings panel + preview pane |
|             | 1. Stat Card (with filters, date range)          | 0.75           | Full config: metric, project, filters  |
|             | 2. Text/Markdown (CodeMirror + viewer)           | 0.5            | Editor + preview (reuse existing)      |
|             | 3. Cost Summary (with project filter)            | 1.25           | Full config: projects, date range      |
|             | - Testing & bug fixes                            | 0.5            | Test all Phase 1 widgets               |
| **Phase 2** | **All 9 Metric Charts (Same Component)**         | **4.25**       | ✅ **PRODUCTION READY**                | All project metrics with settings |
|             | 4. Feedback Scores chart (base + 3 chart types)  | 1.5            | Reusable component (line/bar/area)     |
|             | 5-12. Remaining 8 charts (config only)           | 2              | Schema + testing (0.25 each)           |
|             | _Same component, different metric configs_       |                | Copy-paste efficiency                  |
|             | - Testing & bug fixes                            | 0.75           | Test all chart variations              |
| **Phase 3** | **Experiment & Cost Widgets (Fully Configured)** | **2.25**       | ✅ **FEATURE COMPLETE**                | Experiments + cost with settings  |
|             | 13. Experiment Feedback Scores (reuse existing)  | 0.75           | Wrap existing component + config       |
|             | 14. Experiment Radar Chart (reuse existing)      | 0.5            | Wrap existing component + config       |
|             | 15. Experiment Bar Chart (reuse existing)        | 0.5            | Wrap existing component + config       |
|             | 16. Cost Trend chart                             | 0.5            | Config: projects, chart type, date     |
| **Phase 4** | **Documentation**                                | **0.5**        | ✅ **MVP LAUNCH**                      | Ready for production              |
|             | - Documentation                                  | 0.5            | User guides, widget docs               |
|             | **TOTAL:**                                       | **14 days**    |                                        | **~3 weeks**                      |

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

### Why Incremental Delivery?

- ✅ **Early Feedback:** Demo after 2 weeks, adjust based on user input
- ✅ **Risk Mitigation:** Can stop/pivot if priorities change
- ✅ **User Validation:** Ensure features meet actual needs
- ✅ **Iterative Improvement:** Learn from each phase

### What Can Be Demoed at Each Phase?

| After Week | Phase Complete | Demo Content                                                                                  |
| ---------- | -------------- | --------------------------------------------------------------------------------------------- |
| Week 1     | Phase 0-1      | "Widget config dialog with live preview + 3 fully functional widgets (with filters & config)" |
| Week 2     | Phase 2        | "All 9 metric charts with chart type selector, filters, and full customization"               |
| Week 3     | Phase 3        | "All 16 widgets complete - fully configurable with all settings"                              |
| Week 4     | Phase 4        | "Polished, tested, production-ready MVP with documentation"                                   |

### Dependencies

- ✅ Backend API exists (zero changes needed)
- ✅ Chart library (Recharts) already in use
- ✅ DateRangeSelect component exists
- ✅ Grid layout library decision made (react-grid-layout)

### Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| React Grid Layout learning curve | Medium | Good documentation, active community, 20k+ stars |
| Widget error handling complexity | Medium | Error boundaries, comprehensive testing |
| Performance with 20+ widgets | Low | Lazy loading, virtualization, debounced updates |
| AI-assisted development accuracy | Low | Human review, incremental testing each phase |

---

## 8. Security & Permissions

**MVP Approach:**
- **Workspace-level access:** All workspace members can view/edit all dashboards
- **No per-dashboard permissions** (deferred to Phase 2)
- **Data isolation:** Backend enforces workspace isolation
- **Audit trail:** Creator tracked in `created_by` field

---

## 9. Next Steps

- [ ] Review and approve HLD with stakeholders
- [ ] Create Jira tickets for each phase
- [ ] Assign feature owner (developer)
- [ ] Schedule kickoff meeting
- [ ] Plan Week 1 demo (after Phase 1)

---

**Related Documents:**
- Product Requirements: [Expanding Opik Dashboarding Capabilities](https://www.notion.so/cometml/Expanding-Opik-Dashboarding-Capabilities-28e7124010a380069576e67f947ce5e7)
- MVP Summary: [dashboard-mvp-summary.md](./dashboard-mvp-summary.md)
- Implementation Plan: [dashboard-widgets-implementation-plan.md](./dashboard-widgets-implementation-plan.md)
- Possible Next Steps: [dashboard-widgets-next-steps.md](./dashboard-widgets-next-steps.md)

