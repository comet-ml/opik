# Rebasing PRs after OPIK-5097 structural reorganization

## Path mapping
| Old path | New path |
|---|---|
| `@/components/ui/` | `@/ui/` |
| `@/components/shared/` | `@/shared/` |
| `@/components/layout/` | `@/v1/layout/` |
| `@/components/pages/` | `@/v1/pages/` |
| `@/components/pages-shared/` | `@/v1/pages-shared/` |
| `@/components/App` | `@/v1/App` |
| `@/components/theme-provider` | `@/v1/theme-provider` |
| `@/components/feature-toggles-provider` | `@/v1/feature-toggles-provider` |
| `@/components/server-sync-provider` | `@/v1/server-sync-provider` |
| `@/components/redirect/` | `@/v1/redirect/` |

## Steps
1. `git fetch origin main && git rebase origin/main`
2. Resolve conflicts — apply changes to the new paths
3. Bulk find-replace import paths (see table above)
4. `cd apps/opik-frontend && npm run typecheck && npm run deps:validate`
