# Multi-Project Online Evaluation Rules - Design Decisions

## Overview

This document explains key design decisions made during the implementation of multi-project support for online evaluation rules (OPIK-2883).

## Frontend Design Questions

### 1. Project Limit in Fetch (1000 projects)

**Question**: Is fetching 1000 projects enough, or should we not limit?

**Decision**: **Keep the 1000 limit for now**

**Rationale**:
- 1000 projects should cover 99% of use cases for typical workspace sizes
- Fetching all projects without pagination could cause performance issues for very large workspaces
- If this becomes a limitation, we can:
  - Increase the limit
  - Implement pagination in the UI
  - Add server-side project name resolution in the API response (preferred long-term solution)

**Future Enhancement**: Backend should enrich rules with resolved project names to eliminate this frontend limitation entirely.

---

### 2. Manual Evaluation - Show All Workspace Rules vs Project-Assigned Rules

**Question**: Should manual evaluation dialog show all workspace rules or only rules assigned to specific projects?

**Decision**: **Show all workspace rules** (projectId: undefined)

**Rationale**:
- Manual evaluation is an explicit user action, not automatic scoring
- Users may want to manually apply any rule regardless of project assignments
- This provides maximum flexibility for testing and experimentation
- Project assignment controls automatic execution, not manual testing
- Clearer UX: users understand they can manually test any rule on any trace

**Alternative Considered**: Only show project-assigned rules - rejected because it limits manual testing flexibility.

---

### 3. Allow 0 projectIds or Require At Least 1?

**Question**: Should we allow rules with 0 projects assigned (empty projectIds array)?

**Decision**: **Require at least 1 project** (min: 1 validation)

**Rationale**:
- **For active (enabled) rules**: A rule with no projects assigned is functionally useless
  - It won't execute automatically (no project context to trigger on)
  - It serves no purpose except to exist in the database
- **For disabled rules**: While 0 projects might make sense for "draft" rules, requiring at least 1 project is simpler and clearer
- **Simplifies data model**: Eliminates edge cases in queries and displays
- **User intent**: If a rule has no projects, the user likely:
  - Forgot to select projects (validation catches this error)
  - Wants to disable the rule (use `enabled: false` instead)
  - Wants to delete the rule (use delete action)

**Backend Implementation**: 
- API accepts `projectIds` as `@NotNull Set<UUID>`
- Frontend schema validates `.min(1)` in the form
- No special handling needed for empty sets

**Alternative Considered**: Allow 0 projectIds - rejected as it adds unnecessary complexity for minimal benefit.

---

### 4. Fallback for projectIds[0] - Is it Safe as Default?

**Question**: In `LLMPromptMessagesVariables`, using `projectIds[0] || ""` as fallback - is this safe?

**Context**:
```typescript
projectId={form.watch("projectIds")[0] || ""}
```

**Decision**: **Safe, but could be improved**

**Analysis**:
- **Safe because**: Schema validation requires at least 1 project, so `projectIds[0]` will always exist
- **The `|| ""` fallback**: Acts as defensive programming in case validation is bypassed
- **Edge case**: If validation fails or is removed, empty string is a safe fallback (component handles gracefully)

**Current Implementation**: Acceptable for MVP

**Recommended Enhancement** (optional):
```typescript
// Option 1: Make it explicit with comment
projectId={form.watch("projectIds")[0] || ""} // First project ID or empty (validated: min 1)

// Option 2: Use type-safe access
projectId={form.watch("projectIds").at(0) ?? ""}

// Option 3: Add runtime check
const projectIds = form.watch("projectIds");
projectId={projectIds.length > 0 ? projectIds[0] : ""}
```

---

## Backward Compatibility Decisions

### 5. Update Semantics: Retroactive vs Diff-Based

**Decision**: **Retroactive (delete-then-insert)**, not diff-based

**Rationale**:
- **Simpler implementation**: No need to calculate added/removed projects
- **Clear semantics**: The new `projectIds` set completely replaces the old set
- **Matches user expectations**: When editing a rule, users see and modify the complete list
- **Easier to reason about**: No hidden state or incremental changes
- **API clarity**: One operation with clear contract

**Implementation**:
```java
// Update flow
1. Delete all existing project associations
2. Insert new project associations
3. Clear legacy project_id field
```

**Alternative Considered**: Diff-based update (calculate added/removed) - rejected as unnecessarily complex.

---

## Summary of Decisions

| Question | Decision | Rationale |
|----------|----------|-----------|
| **Project fetch limit** | Keep 1000 | Covers 99% of cases, prevents performance issues |
| **Manual evaluation scope** | All workspace rules | Maximum flexibility for testing |
| **Minimum projectIds** | Require at least 1 | Eliminates useless rules and edge cases |
| **projectIds[0] fallback** | Safe as-is | Schema validation ensures it exists |
| **Update semantics** | Retroactive (delete-insert) | Simpler and clearer than diff-based |

---

## Future Enhancements

1. **Server-side project name resolution**: Backend should include resolved project names in API responses to eliminate frontend fetch limits
2. **Bulk operations**: Support bulk rule updates across multiple projects
3. **Rule templates**: Allow creating rules without projects as templates, marked with special flag
4. **Advanced filtering**: Filter rules by multiple projects, enabled status, etc.

---

**Last Updated**: 2025-12-12
**Related PR**: #4332 (OPIK-2883)

