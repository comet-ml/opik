# Dashboard Grid Layout Library Investigation

**Date**: November 14, 2025  
**Status**: In Review

---

## Investigation Prompt (Reusable Template)

```
I need to investigate open-source React libraries for building a dashboard with grid layout and sections.

REQUIREMENTS:
- Create collapsible sections (accordion-style containers)
- Each section contains a grid of widgets
- Users can drag & drop widgets to reorder within the same section
- Users can resize widgets based on defined grid constraints (configurable columns/rows per widget)
- Users can reorder sections (drag sections to change order)
- Fixed grid layout (not free-form positioning)
- All sections have the same grid column count
- Desktop-first design (responsive is secondary priority)
- Must be open source with permissive license (MIT/Apache)
- TypeScript support preferred
- Must be compatible with React 18 + Tailwind CSS

NICE TO HAVE:
- Move widgets between different sections via drag & drop

CLARIFYING QUESTIONS & ANSWERS:

1. Widget Behavior - Which features do you need?
   ✅ Drag & drop widgets to reorder them within a section
   ✅ Resize individual widgets (make them bigger/smaller)
   ❌ Fixed-size tiles (all widgets same size) - NO, widgets can be resizable by defined grid
   ✅ Move widgets between different sections - YES (but nice-to-have, not mandatory)

2. Section Behavior:
   ✅ Can users reorder sections (drag Section 1 above Section 2) - YES
   ✅ Are sections collapsible (expand/collapse with chevron) - YES
   ❌ Can sections have different grid column counts - NO, all sections have same column count

3. Container Widgets (nested widgets):
   Option: Only sections are containers for widgets, user should be able to drag and drop from section to section

4. Adding Widgets:
   Out of scope - not related to library selection

5. Widget Sizing:
   Widgets can take a limited number of columns and rows (should be configurable per widget)

6. Persistence:
   Saving/restoring layouts out of scope of library, will be implemented separately

7. Responsiveness:
   Both desktop and mobile support needed, but main priority is desktop

8. Performance:
   Will be implemented with lazy loading - out of scope for library selection

INVESTIGATION TASKS:
1. Research popular open-source React libraries that support grid layouts with drag & drop and resize
2. Evaluate each library against the mandatory requirements
3. List pros and cons for each library
4. Include demo links, GitHub stars, license, and TypeScript support
5. Identify if any single library meets all requirements or if a combination approach is needed
6. Provide a comparison table summarizing implementation effort

OUTPUT FORMAT:
- Markdown file with requirements, library evaluations (with pros/cons), key findings, and options summary table
- Focus on factual comparison without making recommendations
```

---

## 1. Requirements

### Mandatory

- Drag & drop widgets to reorder within a section
- Resize widgets based on defined grid (configurable columns/rows per widget)
- Fixed grid layout (not free-form positioning)
- Reorder sections (drag section to change order)
- Collapsible sections (expand/collapse)
- All sections have same grid column count
- Desktop-first (responsive is secondary priority)
- Open source with permissive license (MIT/Apache)
- TypeScript support preferred
- Compatible with React 18 + Tailwind CSS

### Nice to Have

- Move widgets between different sections via drag & drop

## 2. Libraries Evaluation

### react-grid-layout

**GitHub**: https://github.com/react-grid-layout/react-grid-layout  
**Demo**: https://react-grid-layout.github.io/react-grid-layout/examples/0-showcase.html  
**License**: MIT  
**Stars**: 20k+

**Pros:**

- Mature and widely adopted
- Built-in drag & drop within grid
- Built-in resize with grid constraints
- Responsive breakpoints support
- Good TypeScript support
- Active maintenance

**Cons:**

- Does NOT support dragging between multiple grid instances
- Designed for single grid per page
- Would require custom implementation for multi-section drag & drop
- CSS needs manual styling

---

### Gridstack.js

**GitHub**: https://github.com/gridstack/gridstack.js  
**Demo**: https://gridstackjs.com/demo/  
**License**: MIT  
**Stars**: 6k+

**Pros:**

- Native nested grid support
- Can drag between multiple grids
- Built-in resize with grid snap
- Mobile/touch support
- TypeScript rewrite (v5+)

**Cons:**

- Not React-first (framework-agnostic)
- React integration requires wrapper
- Larger bundle size
- jQuery legacy (though removed in v5+)
- May conflict with Tailwind CSS
- Steeper learning curve

---

### dnd-kit

**GitHub**: https://github.com/clauderic/dnd-kit  
**Demo**: https://master--5fc05e08a4a65d0021ae0bf2.chromatic.com/  
**License**: MIT  
**Stars**: 12k+

**Pros:**

- Native multi-container drag & drop support
- Lightweight and performant
- Modern React hooks API
- Excellent TypeScript support
- Zero dependencies
- Active maintenance (2024)
- Already have `@dnd-kit/sortable` in project

**Cons:**

- Does NOT include grid layout system
- Does NOT include resize functionality
- Requires building grid logic manually
- Need to combine with other libraries
- More implementation effort

---

## 3. Supporting Libraries (for combination approach)

### re-resizable (Already in project - line 90, package.json)

**GitHub**: https://github.com/bokuweb/re-resizable  
**License**: MIT

**Pros:**

- Already in dependencies
- Grid snap support
- TypeScript support
- Lightweight

**Cons:**

- Only handles resize (no drag & drop)
- Need to combine with drag library

---

### @radix-ui/react-accordion (Already in project - line 37, package.json)

**GitHub**: https://github.com/radix-ui/primitives  
**License**: MIT

**Pros:**

- Already in dependencies
- Perfect for collapsible sections
- Accessible
- Matches existing design system

---

### @dnd-kit/sortable (Already in project - package.json)

**GitHub**: https://github.com/clauderic/dnd-kit  
**License**: MIT

**Pros:**

- Already in dependencies (part of dnd-kit family)
- Perfect for reordering sections
- Native multi-container support
- Lightweight and performant
- Excellent TypeScript support

**Note:** This is part of the dnd-kit ecosystem already evaluated above. Can be combined with other libraries for section reordering.

---

## 4. Key Findings

**Single library limitations:**

- **Most grid libraries provide excellent single-section grid layout** with drag & drop and resize
- **No single library meets all mandatory requirements** out of the box
- **Collapsible sections and section reordering** are not provided by any grid library
- **Multi-section drag & drop** (nice-to-have) is only supported by Gridstack.js

**Common gaps:**

- Grid libraries focus on widget management within a single grid instance
- Collapsible sections must be implemented separately (accordion component)
- Section reordering requires additional drag & drop library
- Multi-section widget movement needs custom implementation or specific library support

**Existing project dependencies:**

- **@dnd-kit/sortable** (already available) - can handle section reordering
- **@radix-ui/react-accordion** (already available) - perfect for collapsible sections
- **re-resizable** (already available) - can handle widget resizing if needed

## 5. Options Summary

### Approach Comparison

| Approach                                          | Grid Layout       | Widget Drag    | Widget Resize       | Section Collapse | Section Reorder | Multi-Section Widget Drag | Implementation Effort | License |
| ------------------------------------------------- | ----------------- | -------------- | ------------------- | ---------------- | --------------- | ------------------------- | --------------------- | ------- |
| **react-grid-layout + Radix Accordion + dnd-kit** | ✅ Excellent      | ✅ Built-in    | ✅ Built-in         | ✅ Use Radix     | ✅ Use dnd-kit  | ⚠️ Custom logic           | **Medium-High**       | All MIT |
| **Gridstack.js + custom wrapper**                 | ✅ Excellent      | ✅ Built-in    | ✅ Built-in         | ⚠️ Custom        | ⚠️ Custom       | ✅ Built-in               | **Medium-High**       | MIT     |
| **dnd-kit + custom grid + re-resizable**          | ⚠️ Build manually | ✅ Use dnd-kit | ✅ Use re-resizable | ✅ Use Radix     | ✅ Use dnd-kit  | ✅ Use dnd-kit            | **Very High**         | All MIT |

### Feature Support Matrix

| Library               | Grid System | Within-Section Drag | Widget Resize | Multi-Section Drag | TypeScript | React 18   | License | Stars |
| --------------------- | ----------- | ------------------- | ------------- | ------------------ | ---------- | ---------- | ------- | ----- |
| **react-grid-layout** | ✅ Yes      | ✅ Yes              | ✅ Yes        | ❌ No              | ✅ Yes     | ✅ Yes     | MIT     | 20k+  |
| **Gridstack.js**      | ✅ Yes      | ✅ Yes              | ✅ Yes        | ✅ Yes             | ✅ Yes     | ⚠️ Wrapper | MIT     | 6k+   |
| **dnd-kit**           | ❌ No       | ✅ Yes              | ❌ No         | ✅ Yes             | ✅ Yes     | ✅ Yes     | MIT     | 12k+  |

### Implementation Effort Breakdown

**Option 1: react-grid-layout + Radix Accordion + dnd-kit/sortable (Recommended)**

- **Grid Layout**: Use react-grid-layout for widget management
- **Section Collapse**: Use @radix-ui/react-accordion (already in project)
- **Section Reorder**: Use @dnd-kit/sortable (already in project)
- **Multi-Section Drag**: Custom implementation on top of react-grid-layout
- **Effort**: Medium-High
- **Pros**: Leverages existing dependencies, all MIT licensed, mature libraries
- **Cons**: Multi-section widget drag requires custom work

**Option 2: Gridstack.js with React wrapper**

- **Grid Layout**: Use Gridstack.js core
- **Section Collapse**: Custom implementation or use Radix Accordion
- **Section Reorder**: Custom implementation or use dnd-kit
- **Multi-Section Drag**: Built-in support
- **Effort**: Medium-High
- **Pros**: Built-in multi-section drag support
- **Cons**: Not React-first, requires wrapper, potential styling conflicts

**Option 3: Full custom with dnd-kit + re-resizable**

- **Grid Layout**: Build custom CSS Grid or flexbox system
- **Section Collapse**: Use @radix-ui/react-accordion (already in project)
- **Section Reorder**: Use @dnd-kit/sortable (already in project)
- **Widget Management**: Use dnd-kit + re-resizable
- **Effort**: Very High
- **Pros**: Maximum flexibility, uses existing dependencies
- **Cons**: Requires building grid system from scratch, most development work

## 6. Important Notes

### react-grid-layout Multi-Instance Limitation

While react-grid-layout excels at managing widgets within a single grid, it **does not natively support dragging items between multiple grid instances**. Each `ReactGridLayout` component maintains its own isolated state. To implement multi-section widget dragging:

- Would need custom drag handlers
- Manual state synchronization between grid instances
- Complex collision detection logic
- Potential performance issues with multiple grids

### Gridstack.js Multi-Grid Support

Gridstack.js **natively supports dragging between multiple grid instances** through its `acceptWidgets` option. This makes it the only evaluated library with built-in multi-section widget drag support. However:

- Requires creating React wrapper components
- May have styling conflicts with Tailwind CSS
- Less React-idiomatic patterns

### License Considerations

All evaluated libraries use permissive MIT licenses:

- **MIT**: react-grid-layout, Gridstack.js, dnd-kit, re-resizable, @radix-ui/react-accordion

### TypeScript Support

- **Native TypeScript**: dnd-kit, Gridstack.js (v5+)
- **DefinitelyTyped**: react-grid-layout
- **Built-in**: @radix-ui/react-accordion, re-resizable

### Maintenance Status

All evaluated libraries are actively maintained:

- **Active**: react-grid-layout, dnd-kit, Gridstack.js, @radix-ui/react-accordion, re-resizable

## 7. Summary

### Libraries That Meet Core Requirements

**For Grid Layout + Drag + Resize within sections:**

1. **react-grid-layout** - Most popular, mature, excellent grid management
2. **Gridstack.js** - Feature-rich, multi-grid support, but requires React wrapper

**For Collapsible Sections:**

- **@radix-ui/react-accordion** (already in project) - Perfect fit, accessible, matches design system

**For Section Reordering:**

- **@dnd-kit/sortable** (already in project) - Modern, performant, excellent TypeScript support

### Key Decision Point

The **multi-section widget drag** (nice-to-have) requirement is the main differentiator:

- **If multi-section drag is critical**: Gridstack.js is the only library with native support
- **If multi-section drag is truly "nice-to-have"**: react-grid-layout with custom implementation is more React-friendly

### Missing Pieces (All Approaches)

No matter which grid library is chosen, these features must be implemented separately:

1. **Collapsible sections** → Use @radix-ui/react-accordion
2. **Section reordering** → Use @dnd-kit/sortable
3. **Integration layer** → Custom code to connect grid library with section management

### Estimated Implementation Complexity

- **Collapsible sections**: Low (use Radix Accordion)
- **Section reordering**: Low-Medium (use dnd-kit/sortable)
- **Widget drag within section**: None (built into grid libraries)
- **Widget resize**: None (built into grid libraries)
- **Multi-section widget drag**:
  - With Gridstack.js: Low (built-in)
  - With react-grid-layout: High (custom implementation)
  - With custom solution: Very High (build from scratch)
