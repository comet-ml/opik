---
name: opik-frontend
description: React frontend patterns for Opik. Use when working in apps/opik-frontend, on components, state, or data fetching.
---

# Opik Frontend

## Architecture Decisions
- **Routing**: TanStack Router (file-based)
- **Data fetching**: TanStack Query (never raw fetch/useEffect)
- **State**: Zustand for global, React state for local
- **Components**: shadcn/ui + Radix UI base
- **Forms**: React Hook Form + Zod validation

## Critical Gotchas

### Never useEffect for Data Fetching
```typescript
// ❌ BAD
useEffect(() => {
  fetch('/api/data').then(setData);
}, []);

// ✅ GOOD
const { data } = useQuery({
  queryKey: ['data'],
  queryFn: fetchData,
});
```

### Selective Memoization
```typescript
// ✅ USE useMemo for: complex computations, large data transforms
const filtered = useMemo(() =>
  data.filter(x => x.status === 'active').map(transform),
  [data]
);

// ✅ USE useCallback for: functions passed to children
const handleClick = useCallback(() => doSomething(id), [id]);

// ❌ DON'T memoize: simple values, primitives, local functions
const name = data?.name ?? '';  // No useMemo needed
```

### Zustand Selectors
```typescript
// ✅ GOOD - specific selector
const selectedEntity = useEntityStore(state => state.selectedEntity);

// ❌ BAD - selecting entire store causes re-renders
const { selectedEntity, filters } = useEntityStore();
```

## Layer Architecture
```
ui → shared → pages-shared → pages (one-way only)
```
- No circular dependencies
- No cross-page imports
- After modifying imports: `npm run deps:validate`

## State Location Decisions
- **URL state**: filters, pagination, selected items
- **Zustand**: user preferences, cross-component state
- **React state**: form inputs, UI toggles

## Component Structure
```typescript
const Component: React.FC<Props> = ({ prop }) => {
  // 1. State hooks
  // 2. Queries/mutations
  // 3. Memoization (only when needed)
  // 4. Event handlers

  if (isLoading) return <Loader />;
  if (error) return <ErrorComponent />;

  return <div>...</div>;
};
```

## Query Patterns
```typescript
// Query with params
const { data } = useQuery({
  queryKey: [ENTITY_KEY, params],
  queryFn: (context) => fetchEntity(context, params),
});

// Mutation with invalidation
const mutation = useMutation({
  mutationFn: updateEntity,
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: [ENTITY_KEY] });
  },
});
```

## Reference Files
- [forms.md](forms.md) - React Hook Form + Zod patterns
- [ui-components.md](ui-components.md) - Button variants, typography, dark theme
- [responsive-design.md](responsive-design.md) - Tailwind breakpoints vs useIsPhone
- [testing.md](testing.md) - When to test, Vitest patterns
- [code-quality.md](code-quality.md) - Lodash imports, naming, deps:validate
- [performance.md](performance.md) - Bundle optimization, rendering, memoization
