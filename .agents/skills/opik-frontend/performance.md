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
