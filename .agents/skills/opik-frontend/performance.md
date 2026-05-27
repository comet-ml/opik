# Frontend Performance

## Bundle Optimization

### Lazy Load Heavy Components
Use `React.lazy` for components not needed on initial render.

```tsx
// BAD - Monaco bundles with main chunk (~300KB)
import { MonacoEditor } from './monaco-editor';

// GOOD - Monaco loads on demand
const MonacoEditor = lazy(() =>
  import('./monaco-editor').then(m => ({ default: m.MonacoEditor }))
);

function CodePanel({ code }: { code: string }) {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <MonacoEditor value={code} />
    </Suspense>
  );
}
```

## Rendering Performance

### Animate SVG Wrappers
Browsers don't hardware-accelerate CSS animations on SVG elements.

```tsx
// BAD - no hardware acceleration
<svg className="animate-spin">...</svg>

// GOOD - GPU accelerated
<div className="animate-spin">
  <svg>...</svg>
</div>
```

## Re-render Optimization

### Defer State Reads
Don't subscribe to state only used in callbacks.

```tsx
// BAD - re-renders on every searchParams change
function ShareButton({ id }: Props) {
  const searchParams = useSearchParams();
  const handleShare = () => {
    const ref = searchParams.get('ref');
    share(id, { ref });
  };
  return <button onClick={handleShare}>Share</button>;
}

// GOOD - reads on demand, no subscription
function ShareButton({ id }: Props) {
  const handleShare = () => {
    const params = new URLSearchParams(window.location.search);
    share(id, { ref: params.get('ref') });
  };
  return <button onClick={handleShare}>Share</button>;
}
```

### Extract Memoized Components
Move expensive work after early returns.

```tsx
// BAD - computes avatar even when loading
function Profile({ user, loading }: Props) {
  const avatar = useMemo(() => computeAvatarId(user), [user]);
  if (loading) return <Skeleton />;
  return <Avatar id={avatar} />;
}

// GOOD - skips computation when loading
const UserAvatar = memo(function({ user }: { user: User }) {
  const id = useMemo(() => computeAvatarId(user), [user]);
  return <Avatar id={id} />;
});

function Profile({ user, loading }: Props) {
  if (loading) return <Skeleton />;
  return <UserAvatar user={user} />;
}
```

Note: If React Compiler is enabled, manual memoization isn't necessary.

## Data Fetching

### Don't refetch what the parent already has
If a parent already holds an entity (typically from a list query), pass it down instead of having the child refetch by id. Keep the per-id fetch as a fallback for when the entity isn't in the list.

```tsx
// BAD - parent has the prompt from useProjectPromptsList,
// but child fires a second request for the same one
const CompactLoadedPrompt = ({ promptId }: Props) => {
  const { data } = usePromptById({ promptId });
  return <LoadedPromptDisplay {...derive(data)} />;
};

// GOOD - accept the entity as a prop; only fetch when not provided
type Props = { promptId: string; prompt?: Prompt };
const CompactLoadedPrompt = ({ promptId, prompt }: Props) => {
  const { data: fetched } = usePromptById(
    { promptId },
    { enabled: !!promptId && !prompt },
  );
  const data = prompt ?? fetched;
  return <LoadedPromptDisplay {...derive(data)} />;
};
```

**Why:** A list query already returns full `Prompt` objects with `latest_version`, `template_structure`, and `version_count`. Refetching by id costs an extra round-trip per loaded prompt and creates a cache slot duplicating the list's data. The `enabled` guard lets the child still fetch when used in a context that doesn't have the entity in scope.

### Don't narrow query invalidation past correctness
After a mutation that has cross-entity side effects, invalidating the whole keyspace is sometimes the only correct option — narrowing it is a regression, not a cleanup.

```ts
// CORRECT — env can be transferred from version B to version A,
// so version B's cache becomes stale too. We don't have B's id here.
onSuccess: (_data, { promptId }) => {
  queryClient.invalidateQueries({ queryKey: ["prompt", { promptId }] });
  queryClient.invalidateQueries({
    predicate: (q) =>
      q.queryKey[0] === "prompt-versions" &&
      (q.queryKey[1] as { promptId?: string })?.promptId === promptId,
  });
  queryClient.invalidateQueries({ queryKey: ["prompt-version"] }); // broad, on purpose
}
```

**Why:** `prompt-version` cache keys are by `versionId` only — no `promptId`. When env moves from B→A, only the page state knows about B. The broad invalidate is the safest signal; individual version caches are small. See [useSetPromptVersionEnvironmentMutation.ts](../../../apps/opik-frontend/src/api/prompts/useSetPromptVersionEnvironmentMutation.ts).

**How to apply:** Before tightening an invalidate, list every entity whose cache could go stale after the mutation succeeds. If the mutation has transfer/move semantics (env-pin, default-version, primary-tag), the "off-target" entity loses state too. Keep the broad invalidate unless every affected entity is reachable from the mutation handler.
