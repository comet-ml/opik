import type { DatasetVersionRef } from './client';

/**
 * One dataset item entry for `PUT /v1/private/datasets/items`, stamped with a
 * `revision` tag so two writes to the same id carry visibly different content
 * (useful for asserting "last write wins" on an upsert).
 */
export function buildDatasetItem(
  id: string,
  revision: string,
): { id: string; data: Record<string, unknown> } {
  return { id, data: { input: `input ${revision}`, expected_output: `output ${revision}` } };
}

/** Split `ids` into `count` equal batches of `batchSize`, stamped with `revision`. */
export function buildDatasetItemBatches(
  ids: string[],
  count: number,
  batchSize: number,
  revision: string,
) {
  return Array.from({ length: count }, (_, i) =>
    ids.slice(i * batchSize, (i + 1) * batchSize).map((id) => buildDatasetItem(id, revision)),
  );
}

/** Sums one counter field across a set of dataset versions. */
export function sumDatasetVersionField(
  versions: DatasetVersionRef[],
  field: 'itemsAdded' | 'itemsModified',
): number {
  return versions.reduce((acc, v) => acc + v[field], 0);
}
