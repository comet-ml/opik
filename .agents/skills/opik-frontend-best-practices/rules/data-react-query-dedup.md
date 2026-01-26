---
title: Use React Query for Automatic Deduplication
impact: MEDIUM-HIGH
impactDescription: automatic deduplication and caching
tags: client, react-query, deduplication, data-fetching, caching
---

## Use React Query for Automatic Deduplication

React Query (TanStack Query) enables automatic request deduplication, caching, and revalidation across component instances. Multiple components requesting the same data will share a single network request.

**Incorrect (no deduplication, each instance fetches):**

```tsx
function UserList() {
  const [users, setUsers] = useState([])
  useEffect(() => {
    fetch('/api/users')
      .then(r => r.json())
      .then(setUsers)
  }, [])
}
```

**Correct (multiple instances share one request):**

```tsx
import { useQuery } from '@tanstack/react-query'

function UserList() {
  const { data: users } = useQuery({
    queryKey: ['users'],
    queryFn: () => api.get('/api/users')
  })
}
```

**With query parameters:**

```tsx
import { useQuery } from '@tanstack/react-query'

function UserList({ workspaceName }: { workspaceName: string }) {
  const { data: users } = useQuery({
    queryKey: ['users', workspaceName],
    queryFn: () => api.get('/api/users', { 
      params: { workspace_name: workspaceName } 
    })
  })
}
```

**For static/immutable data:**

```tsx
import { useQuery } from '@tanstack/react-query'

function StaticContent() {
  const { data } = useQuery({
    queryKey: ['config'],
    queryFn: () => api.get('/api/config'),
    staleTime: Infinity, // Never refetch
    gcTime: Infinity // Never garbage collect
  })
}
```

**For mutations:**

```tsx
import { useMutation, useQueryClient } from '@tanstack/react-query'

function UpdateButton() {
  const queryClient = useQueryClient()
  
  const mutation = useMutation({
    mutationFn: (data) => api.put('/api/user', data),
    onSuccess: () => {
      // Invalidate and refetch
      queryClient.invalidateQueries({ queryKey: ['users'] })
    }
  })
  
  return (
    <button onClick={() => mutation.mutate({ name: 'New Name' })}>
      Update
    </button>
  )
}
```

**Benefits:**

- **Automatic deduplication**: Multiple components requesting the same data share one request
- **Smart caching**: Data is cached and reused across the app
- **Background refetching**: Stale data is automatically refetched in the background
- **Optimistic updates**: Update UI immediately before server responds
- **Query invalidation**: Easily invalidate and refetch related queries after mutations

Reference: [https://tanstack.com/query](https://tanstack.com/query)
