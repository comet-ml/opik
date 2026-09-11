# Endpoint Permissions

## When Adding or Modifying Resource Endpoints

Every new JAX-RS endpoint method in `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/` should be evaluated for a `@RequiredPermissions` annotation.

### Check Process

1. **Read the current permissions enum** at `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/auth/WorkspaceUserPermission.java`.

2. **Check if the endpoint's resource already uses `@RequiredPermissions`** on other methods. If sibling methods have permissions, the new endpoint likely needs one too.

3. **Logically match the endpoint's operation to a permission**. Do not rely on naming patterns — reason about what the endpoint does and which permission governs that action:
   - A read/list/get endpoint likely maps to a **view** permission for that domain entity
   - A create/write/log endpoint likely maps to a **create**, **write**, or **log** permission
   - An update/edit/modify endpoint likely maps to an **edit** or **update** permission
   - A delete/remove/purge endpoint likely maps to a **delete** permission
   - An endpoint may map to a permission from a different entity group if it crosses domain boundaries (e.g., annotating from a queue context uses a trace-level annotation permission)

4. **If a logically matching permission exists**, do NOT add it automatically. Instead, inform the user which permission you believe matches and why, then ask for confirmation before adding the `@RequiredPermissions` annotation.

5. **If no matching permission exists**, inform the user:
   - State which resource/operation has no matching permission
   - Note that the endpoint will fall back to team-membership authentication
   - Ask whether a new permission should be added to the enum, or if the fallback is acceptable

### Example

```java
@GET
@Path("/{id}")
@RequiredPermissions(WorkspaceUserPermission.DATASET_VIEW)
public Response getDatasetById(@PathParam("id") UUID id) { ... }
```

### Current Coverage

Not all resources have permissions defined yet. The remaining endpoints rely on team-membership authentication. This is expected. Do not add permissions speculatively; only add them when a logically matching `WorkspaceUserPermission` value exists or the user confirms a new one should be created.

### Reference

Full permissions spec: https://www.notion.so/cometml/Workspace-permissions-and-user-roles-management-2b77124010a380f8b526e7ecb235c419
