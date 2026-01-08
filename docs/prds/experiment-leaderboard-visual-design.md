# Experiment Leaderboard Widget - Visual Design Requirements

**Date**: January 7, 2026  
**Status**: Implemented  
**Related PRD**: [PRD: Experiment Leaderboard Widget](https://www.notion.so/cometml/PRD-Experiment-Leaderboard-Widget-2e17124010a381979e14fcb569d82b09)

---

## Unified Column Selector

The column selector in the configuration modal includes all columns in a single unified list, grouped by category:
- **Standard Columns**: Experiment name, Dataset, Created at
- **Quality Metrics**: Accuracy, Hallucination, Relevance, Helpfulness, Mean Score
- **Efficiency Metrics**: Duration, Cost, Trace Count
- **Configuration Columns**: User-defined metadata columns

Each category has a "Select all" / "Deselect all" button for quick selection.

---

## Summary

This document outlines the visual design and UX requirements for the Experiment Leaderboard Widget redesign. The design prioritizes visual hierarchy, scanability, and semantic clarity while maintaining a calm, professional B2B aesthetic.

---

## Design Philosophy

The leaderboard table must be **visually elegant, highly scannable, and decision-oriented**, while maintaining a **calm, professional B2B aesthetic**. The design prioritizes visual hierarchy, scanability, and semantic clarity over decorative elements.

---

## Visual Design Requirements

### 1. Visual Hierarchy

#### Leaderboard Title & Metadata
- **Widget title**: Prominent display with larger font size and stronger weight
- **Secondary information**: Dataset name, trace count, and metadata visually de-emphasized (smaller font, muted color)
- **Clear separation**: Visual distinction between header, body, and control areas

#### Rank Emphasis
- **Rank column**: Enlarged with icons for top 3 positions (Trophy, Medal, Award)
- **Top 3 highlighting**: Subtle background tints and left border accents
  - **Rank 1**: Amber tones with "Best overall" badge
  - **Rank 2**: Slate tones
  - **Rank 3**: Orange tones
- **Restrained medal semantics**: Muted color palette, no aggressive gamification

#### Column Grouping
- **Quality metrics group**: Accuracy, Hallucination, Relevance, Helpfulness with shared background tint
- **Efficiency metrics group**: Duration, Cost, Trace Count with distinct background tint
- **Grouping method**: Header backgrounds and section spacing (not heavy borders)

### 2. Metric Visual Encoding

- **Bar indicators**: Subtle thin bars behind numeric values showing relative performance
- **Inverted semantics**: Lower values are better for hallucination, duration, cost (visualized accordingly)
- **Opacity-based**: Use opacity and scale, not strong color fills
- **Tabular numerals**: All numeric values use tabular numerals for alignment

### 3. Top Row Emphasis

- **#1 entry elevation**: Slight background differentiation and "Best overall" badge
- **Visual consistency**: Still maintains table structure and consistency

### 4. Typography & Alignment

- **Tabular numerals**: All metrics use tabular numerals (monospaced digits)
- **Right alignment**: All numeric columns right-aligned
- **Consistent precision**: Normalized decimal places (3 for scores, 2 for cost, 1 for duration)
- **Row height**: Increased for breathing room (comfortable mode: 16px, compact mode: 12px)

### 5. Header Polish

- **Metric icons**: Small, subtle icons for metric types (Target, TrendingUp, Clock, DollarSign, etc.)
- **Sort affordance**: Active sort column clearly emphasized, inactive columns visually subdued
- **Hover states**: Improved visual feedback on column headers

### 6. Density Control

- **Two modes**: Comfortable (default) and Compact
- **Toggle**: Subtle density toggle button (appears on hover)
- **Progressive disclosure**: Optional for secondary metrics

### 7. Control De-emphasis

- **Action buttons**: Fade until hover (opacity-0 → opacity-100 on group-hover)
- **Icon-first**: Prefer icon-based controls over text labels
- **Non-competing**: Controls do not compete with data for attention

---

## Design Constraints

- **Color usage**: Use color sparingly and semantically (good/bad/expensive/slow)
- **No heatmaps**: Avoid heatmap overload or rainbow tables
- **Professional aesthetic**: Calm, neutral palette with restrained emphasis
- **No gamification**: Avoid flashy gradients or game-like elements
- **Light theme primary**: Design optimized for light theme (dark theme optional)

---

## Acceptance Criteria

- ✅ Strong visual hierarchy with prominent title and de-emphasized metadata
- ✅ Top 3 rows visually distinguished with subtle styling
- ✅ Column grouping clearly visible (quality vs efficiency metrics)
- ✅ Metric bars/indicators provide visual context without overwhelming
- ✅ Tabular numerals ensure proper numeric alignment
- ✅ Density toggle allows users to adjust table spacing
- ✅ Controls fade until hover, maintaining focus on data
- ✅ Professional B2B aesthetic maintained throughout

---

## Updated UI Components

### Enhanced Components

- **LeaderboardTable**: Redesigned table with visual hierarchy and grouping
- **RankColumn**: Enhanced rank display with icons (Trophy, Medal, Award) for top 3
- **MetricCell**: Metric values with subtle bar indicators and tabular numerals
- **DensityToggle**: Comfortable/Compact view toggle (appears on hover)
- **ColumnGrouping**: Visual grouping for quality vs efficiency metrics
- **MetricIcons**: Small icons for metric types (Target, Clock, DollarSign, etc.)
- **BarIndicators**: Subtle background bars showing relative metric performance

---

## Implementation Details

### Visual Styling

1. **Top 3 Rows**:
   - Rank 1: Amber background tint, left border, "Best overall" badge
   - Rank 2: Slate background tint, left border
   - Rank 3: Orange background tint, left border
   - Icons: Trophy (1st), Medal (2nd), Award (3rd)

2. **Column Grouping**:
   - Quality metrics: `bg-primary/5` background tint
   - Efficiency metrics: `bg-blue-50/30 dark:bg-blue-950/10` background tint

3. **Metric Bars**:
   - Thin horizontal bars (height: 1.5px) behind numeric values
   - Width represents percentage of max value
   - Inverted for metrics where lower is better

4. **Typography**:
   - Tabular numerals: `tabular-nums` class for all numeric values
   - Right alignment: `text-right` for all metric columns
   - Consistent font weights: Medium for values, Semibold for headers

5. **Density Modes**:
   - Comfortable: `h-16` rows, `px-4 py-4` padding
   - Compact: `h-12` rows, `px-3 py-3` padding

---

## Updated Functional Requirements

### FR9: Visual Design & Hierarchy
- **Requirement**: Table must establish strong visual hierarchy and improve scanability
- **Acceptance Criteria**:
  - Widget title is prominently displayed
  - Top 3 rows have subtle visual distinction
  - Column grouping is clearly visible
  - Metric bars provide visual context
  - Tabular numerals ensure alignment
  - Density toggle available
  - Controls fade until hover

---

## Related Files

### Modified Files
- `apps/opik-frontend/src/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/ExperimentLeaderboardWidget.tsx`
  - Redesigned table rendering with visual hierarchy
  - Added metric bar indicators
  - Implemented column grouping
  - Added density toggle
  - Enhanced rank display with icons
  - Improved typography with tabular numerals

---

**End of Document**

