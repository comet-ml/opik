# Experiment Leaderboard Widget - New Requirements

**Date**: January 7, 2026  
**Status**: Implemented  
**Related PRD**: [PRD: Experiment Leaderboard Widget](https://www.notion.so/cometml/PRD-Experiment-Leaderboard-Widget-2e17124010a381979e14fcb569d82b09)

## Summary

This document outlines the new requirements that have been implemented for the Experiment Leaderboard Widget beyond the original PRD. These enhancements improve usability, accessibility, and user experience.

---

## New Requirements

### 1. Enhanced Metric Selector

**Requirement**: Users can select which metrics to display with a checkbox-based selector that follows Opik's standard UI patterns.

**Acceptance Criteria**:
- **Default behavior**: Empty `selectedMetrics` array means "show all available metrics" (default state)
- **Metric selector UI**: Checkbox list with "All metrics" master checkbox
- **Individual selection**: Users can check/uncheck individual metrics
- **Select all toggle**: When all metrics are explicitly selected, automatically sets to "show all" (empty array)
- **Selection count**: Shows count of selected metrics when partial selection (e.g., "5 of 8 selected")
- **Available metrics**: Includes all feedback scores and system metrics (duration, cost, trace_count)
- **Preview integration**: Widget preview updates in real-time as metrics are selected/deselected

**Implementation Details**:
- Uses same checkbox pattern as "Standard columns" selector
- `selectedMetrics` field in widget config schema (array of strings, default: empty array)
- Empty array = show all, non-empty array = show only selected metrics

---

### 2. Column Reordering

**Requirement**: Users can reorder table columns using the standard Opik column reordering pattern (ColumnsButton component).

**Acceptance Criteria**:
- **ColumnsButton integration**: Widget header includes ColumnsButton component (standard Opik pattern)
- **Column data structure**: All columns (rank, name, dataset, metadata, metrics, system metrics) available for reordering
- **Drag-and-drop reordering**: Users can drag columns to reorder within the ColumnsButton dropdown
- **Order persistence**: Column order saved in widget configuration (`columnsOrder` field)
- **Default order**: Empty `columnsOrder` array means default column order (rank, name, dataset, metrics, system metrics)
- **Real-time preview**: Column order changes reflect immediately in widget preview
- **Consistent UX**: Uses same column reordering pattern as other Opik tables (TracesSpansTab, ProjectsPage, etc.)

**Implementation Details**:
- Added `columnsOrder` field to widget config schema (array of strings, default: empty array)
- Created unified `ColumnData` structure for all columns
- Uses `sortColumnsByOrder` utility from `@/lib/table`
- ColumnsButton positioned in widget header next to actions menu
- Dynamic table rendering based on ordered columns

---

### 3. Full Width Widget by Default

**Requirement**: Widget defaults to full width when added to dashboard.

**Acceptance Criteria**:
- **Default size**: Widget starts at full width (6 columns in 6-column grid system)
- **Default height**: Widget starts at 6 grid units tall
- **Minimum constraints**: Widget can be resized but maintains minimum 4 columns wide and 4 rows tall
- **Resizable**: Users can still resize widget after adding (maintains flexibility)
- **Layout integration**: Size configuration integrated with dashboard grid layout system

**Implementation Details**:
- Updated `getWidgetSizeConfig` in `apps/opik-frontend/src/lib/dashboard/layout.ts`
- Added case for `WIDGET_TYPE.EXPERIMENT_LEADERBOARD`:
  - `w: 6` (full width)
  - `h: 6` (default height)
  - `minW: 4` (minimum width)
  - `minH: 4` (minimum height)

---

### 4. Enhanced Clickable Experiment Rows

**Requirement**: Experiment rows provide clear visual feedback and accessibility for click interactions.

**Acceptance Criteria**:
- **Visual indicators**: Experiment name styled as clickable link (`text-primary` with `hover:underline`)
- **Hover effects**: Enhanced hover states (`hover:bg-primary/5` and `hover:shadow-sm`) for better visual feedback
- **Keyboard accessibility**: Rows support keyboard navigation (Enter/Space keys trigger navigation)
- **ARIA support**: Rows have `role="button"`, `tabIndex={0}`, and descriptive `aria-label` attributes
- **Smooth transitions**: `transition-all` for smoother hover effects
- **Group hover coordination**: Uses `group` class for coordinated hover effects across row elements

**Implementation Details**:
- Experiment name cell uses `text-primary hover:underline` classes
- TableRow uses `group` class and enhanced hover states
- Added `onKeyDown` handler for Enter/Space keys
- Added ARIA attributes for screen reader support
- Navigation uses correct route format: `/$workspaceName/experiments/$datasetId/compare` with search params

---

### 5. Unified Column Selector

**Requirement**: The column selector in the configuration modal should include all columns in a single unified list instead of having separate dropdowns per "type".

**Acceptance Criteria**:
- **Single unified list**: All columns (standard, metrics, metadata) appear in one unified checkbox list
- **Grouped by category**: Columns are visually grouped by category:
  - Standard Columns: Experiment name, Dataset, Created at
  - Quality Metrics: Accuracy, Hallucination, Relevance, Helpfulness, Mean Score
  - Efficiency Metrics: Duration, Cost, Trace Count
  - Configuration Columns: User-defined metadata columns
- **Category-level selection**: "Select all" / "Deselect all" buttons for each category
- **Individual selection**: Users can check/uncheck individual columns
- **Metadata management**: Metadata columns can be added via input field and appear in the unified list
- **Always-visible columns**: Columns like "name" are properly disabled and marked as "always visible"
- **Real-time preview**: Widget preview updates as columns are selected/deselected

**Implementation Details**:
- Replaced three separate sections (Standard columns, Metric columns, Configuration columns) with single unified selector
- Uses `useExperimentsFeedbackScoresNames` hook to fetch all available metrics
- Creates unified `allAvailableColumns` list combining:
  - Standard columns from `STANDARD_COLUMNS` constant
  - Metrics from API (grouped as quality or efficiency)
  - Metadata columns from user input
- Grouped display with visual separators (borders) between categories
- Category-level "Select all" / "Deselect all" functionality
- Maintains backward compatibility with existing config structure (`displayColumns`, `selectedMetrics`, `metadataColumns`)

---

## Updated Configuration Schema

The widget configuration now includes:

```typescript
{
  filters: Filters;
  selectedMetrics: string[];        // NEW: Empty = show all, non-empty = show selected
  primaryMetric: string;
  sortOrder: "asc" | "desc";
  showRank: boolean;
  maxRows: number;
  displayColumns: string[];        // Standard columns (name, dataset, duration, cost, etc.)
  metadataColumns: string[];        // User-defined metadata columns
  columnsOrder: string[];           // NEW: Empty = default order, non-empty = custom order
}
```

**Note**: The unified column selector UI presents all columns together, but internally still uses the existing `displayColumns`, `selectedMetrics`, and `metadataColumns` fields for backward compatibility.

---

## Updated Functional Requirements

### FR3a: Enhanced Metric Selector
- Users can select which metrics to display
- Default shows all metrics (empty selection)
- Checkbox-based UI with "All metrics" master checkbox
- Real-time preview updates

### FR3b: Unified Column Selector
- All columns (standard, metrics, metadata) in single unified list
- Grouped by category with visual separation
- Category-level "Select all" / "Deselect all" functionality
- Individual column checkboxes
- Metadata columns can be added and appear in unified list

### FR3c: Column Reordering
- Users can reorder columns via ColumnsButton
- Column order persists in widget config
- Uses standard Opik column reordering pattern

### FR6a: Enhanced Clickable Rows
- Clear visual feedback for clickable rows
- Keyboard accessibility support
- Improved hover effects

### FR7a: Widget Layout Configuration
- Widget defaults to full width (6 columns)
- Default height of 6 grid units
- Minimum size constraints (4x4)

---

## Updated Component Areas

### Frontend Changes

**New/Updated Components**:
- `ExperimentLeaderboardWidget.tsx`: 
  - Added `allColumnsData` structure
  - Added `orderedColumns` logic
  - Added `handleColumnsOrderChange` callback
  - Enhanced row click handlers
  - Dynamic column rendering

- `ExperimentLeaderboardWidgetEditor.tsx`:
  - Added unified column selector replacing three separate sections
  - Uses `useExperimentsFeedbackScoresNames` to fetch available metrics
  - Creates unified `allAvailableColumns` list with category grouping
  - Category-level "Select all" / "Deselect all" functionality
  - Integrated with preview widget updates

- `schema.ts`:
  - Added `selectedMetrics` field (array, default: [])
  - Added `columnsOrder` field (array, default: [])

- `helpers.ts`:
  - Updated default config to include `columnsOrder: []`

**Layout Configuration**:
- `apps/opik-frontend/src/lib/dashboard/layout.ts`:
  - Added `EXPERIMENT_LEADERBOARD` case to `getWidgetSizeConfig`
  - Set default size to `w: 6, h: 6, minW: 4, minH: 4`

**Type Definitions**:
- `apps/opik-frontend/src/types/dashboard.ts`:
  - Added `columnsOrder?: string[]` to `ExperimentLeaderboardWidgetType`

---

## Testing Requirements

### New Test Scenarios

1. **Unified Column Selector**:
   - Test all columns appear in single unified list
   - Test columns are grouped by category correctly
   - Test category-level "Select all" / "Deselect all" buttons
   - Test individual column selection/deselection
   - Test metadata columns can be added and appear in list
   - Test always-visible columns are properly disabled
   - Test preview updates when columns change

2. **Metric Selector** (within unified selector):
   - Test "All metrics" default (empty selection shows all)
   - Test individual metric selection/deselection
   - Test "Select all" toggle behavior for quality/efficiency categories
   - Test preview updates when metrics change

3. **Column Reordering**:
   - Test ColumnsButton appears in widget header
   - Test drag-and-drop column reordering
   - Test column order persists after dashboard save/reload
   - Test default order when `columnsOrder` is empty

4. **Full Width Layout**:
   - Test widget starts at full width when added
   - Test widget can be resized (minimum 4x4)
   - Test widget maintains size after dashboard reload

5. **Enhanced Clickable Rows**:
   - Test experiment name appears as clickable link
   - Test hover effects on rows
   - Test keyboard navigation (Enter/Space)
   - Test screen reader accessibility

---

## Definition of Done Updates

### Additional Acceptance Criteria

- ✅ Unified column selector implemented (all columns in single list)
- ✅ Columns grouped by category with visual separation
- ✅ Category-level "Select all" / "Deselect all" functionality
- ✅ Metric selector integrated into unified selector
- ✅ Column reordering via ColumnsButton integrated
- ✅ Widget defaults to full width when added
- ✅ Enhanced clickable rows with improved UX and accessibility
- ✅ All new configuration fields persist correctly
- ✅ Widget preview updates in real-time for all new features
- ✅ No TypeScript errors
- ✅ All linter checks passing
- ✅ Build succeeds without errors

---

## Related Files

### Modified Files
- `apps/opik-frontend/src/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/ExperimentLeaderboardWidget.tsx`
- `apps/opik-frontend/src/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/ExperimentLeaderboardWidgetEditor.tsx`
- `apps/opik-frontend/src/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/schema.ts`
- `apps/opik-frontend/src/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/helpers.ts`
- `apps/opik-frontend/src/lib/dashboard/layout.ts`
- `apps/opik-frontend/src/types/dashboard.ts`

### New Dependencies
- `ColumnsButton` component (already exists in codebase)
- `sortColumnsByOrder` utility (already exists in codebase)
- `ColumnData` type (already exists in codebase)

---

## Notes

- All new features follow existing Opik UI patterns
- No breaking changes to existing functionality
- Backward compatible (empty arrays = default behavior)
- All features tested and working in prototype

---

**End of Document**

