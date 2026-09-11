# UI Permissions

## When Adding or Modifying Components

Every new or modified component in `apps/opik-frontend/src/` that performs a guarded action (create, delete, edit, configure, or view gated content) should be evaluated for permission guards.

### Check Process

1. **Read the current permissions** in `apps/opik-frontend/src/plugins/comet/PermissionsProvider.tsx`. This file lists all available permission flags (e.g., `canCreateDatasets`, `canDeleteTraces`, `canViewDashboards`).

2. **Check if sibling or similar components use `usePermissions()`**. If nearby components in the same page or feature already consume permissions, the new component likely needs them too.

3. **Logically match the UI action to a permission flag**. Do not rely on naming patterns — reason about what the component does and which permission governs that action:
   - A page or section displaying gated content likely needs a **view** permission (e.g., `canViewExperiments`, `canViewDashboards`, `canViewDatasets`)
   - A "Create" / "Add" / "New" button likely needs a **create** permission (e.g., `canCreateDatasets`, `canCreateProjects`)
   - An "Edit" / "Update" / "Save" action likely needs an **edit** permission (e.g., `canEditDashboards`, `canEditDatasets`)
   - A "Delete" / "Remove" button or menu item likely needs a **delete** permission (e.g., `canDeleteTraces`, `canDeletePrompts`)
   - A configuration or settings action likely needs a **configure/update** permission (e.g., `canConfigureWorkspaceSettings`, `canUpdateAIProviders`)
   - Annotation, tagging, and comment actions have their own permissions (`canAnnotateTraceSpanThread`, `canTagTrace`, `canWriteComments`)

4. **If a logically matching permission exists**, do NOT add it automatically. Instead, inform the user which permission you believe matches and why, then ask for confirmation before adding the guard.

5. **If no matching permission exists**, inform the user:
   - State which UI action has no matching permission
   - Note that without a guard, the action will be available to all authenticated users
   - Ask whether a new permission should be added to the system, or if the current behavior is acceptable

### Guard Patterns

When applying a permission, use the pattern appropriate to the context:

**Page-level access control** — wrap entire pages with a guard component:
```tsx
const { permissions: { canViewDashboards } } = usePermissions();

return (
  <NoAccessPageGuard resourceName="dashboards" canViewPage={canViewDashboards} />
);
```

**Conditional rendering of actions** — hide buttons or menu items:
```tsx
const { permissions: { canCreateDatasets } } = usePermissions();

return (
  <>
    {canCreateDatasets && (
      <Button onClick={handleCreate}>Create dataset</Button>
    )}
  </>
);
```

**Disabling queries** — prevent unnecessary API calls:
```tsx
const { permissions: { canViewDatasets } } = usePermissions();

const { data } = useDatasetsList(params, {
  enabled: canViewDatasets,
});
```

**Read-only component states** — render immutable versions instead of hiding:
```tsx
const { permissions: { canTagTrace } } = usePermissions();

const tagsProps = canTagTrace
  ? { tags }
  : { tags: [], immutableTags: tags };
```

### Current Coverage

Not all UI actions have permission guards yet. This is expected. Do not add guards speculatively; only add them when a logically matching permission flag exists in `PermissionsProvider.tsx` or the user confirms a new one should be created.

### Common Mistakes

- **Forgetting to guard delete actions** — delete buttons in row action menus and bulk action panels are easy to miss
- **Forgetting navigation visibility** — sidebar menu items should be hidden or disabled based on view permissions (see `SideBarMenuItems.tsx`)
- **Forgetting to disable queries** — even if a button is hidden, the underlying query may still fire; use `enabled` to prevent this
- **Inconsistent v1/v2 coverage** — if a permission guard is added in `v1/`, the equivalent `v2/` component should also be guarded, and vice versa

### Reference

- Permissions type definition: `apps/opik-frontend/src/types/permissions.ts`
- Permission context hook: `apps/opik-frontend/src/contexts/PermissionsContext.tsx`
- Permission provider (Comet plugin): `apps/opik-frontend/src/plugins/comet/PermissionsProvider.tsx`
- Permission computation hook: `apps/opik-frontend/src/plugins/comet/useUserPermission.ts`
- Backend permission names enum: `apps/opik-frontend/src/plugins/comet/types.ts` (`ManagementPermissionsNames`)
- Full permissions spec: https://www.notion.so/cometml/Workspace-permissions-and-user-roles-management-2b77124010a380f8b526e7ecb235c419
