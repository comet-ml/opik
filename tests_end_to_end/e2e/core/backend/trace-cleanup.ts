import type { BackendClient } from './client';

/**
 * Delete traces, retrying each id on its own if the batch call fails.
 *
 * `TraceService.delete` groups the ids by weekly partition server-side and
 * abandons the whole request when one partition's statement fails, so a single
 * failing id would otherwise strand every healthy trace sent with it. The
 * per-id retry isolates that — the ids that share a partition with the bad one
 * are still attempted individually.
 *
 * Nothing here throws: teardown must never replace the test's own error, which
 * is why each attempt only warns. An empty list is a no-op rather than a call,
 * because the endpoint requires at least one id.
 */
export async function deleteTracesResilient(
  backendClient: BackendClient,
  ids: string[],
  label: string,
): Promise<void> {
  if (ids.length === 0) return;

  try {
    await backendClient.deleteTraces(ids);
    return;
  } catch (err) {
    console.warn(`[${label}] batch trace delete failed, retrying per id:`, err);
  }

  for (const id of ids) {
    try {
      await backendClient.deleteTraces([id]);
    } catch (err) {
      console.warn(`[${label}] could not delete trace ${id}:`, err);
    }
  }
}
