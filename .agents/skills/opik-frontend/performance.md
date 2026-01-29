# Frontend Performance

## Bundle Optimization

### Avoid Barrel Imports
Import directly from source files instead of barrel files (index.js re-exports).

```tsx
// BAD - loads 1,583 modules, 200-800ms per cold start
import { Check, X } from 'lucide-react';

// GOOD - loads only what you need
import Check from 'lucide-react/dist/esm/icons/check';
import X from 'lucide-react/dist/esm/icons/x';
```

Affected libraries: `lucide-react`, `@radix-ui/*`, `lodash`, `date-fns`, `rxjs`.

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

### CSS content-visibility for Long Lists
Apply `content-visibility: auto` to defer off-screen rendering.

```css
.list-item {
  content-visibility: auto;
  contain-intrinsic-size: 0 80px; /* estimated height */
}
```

For 1000 items, browser skips layout/paint for ~990 off-screen items (10x faster initial render).

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

### Parallel Fetching
Fetch independent data in parallel with Promise.all.

```tsx
// BAD - sequential, 2x latency
const users = await fetchUsers();
const projects = await fetchProjects();

// GOOD - parallel
const [users, projects] = await Promise.all([
  fetchUsers(),
  fetchProjects()
]);
```

### Preload on Hover
Start loading on hover for perceived instant navigation.

```tsx
const prefetch = useRouter().prefetch;

<Link
  to="/details/$id"
  onMouseEnter={() => prefetch('/details/$id')}
>
  View Details
</Link>
```
