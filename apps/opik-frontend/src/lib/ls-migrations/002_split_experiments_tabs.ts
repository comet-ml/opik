/**
 * Migration 002: Split the Experiments page settings per tab
 *
 * Context: The Experiments page split into Test suites and Datasets tabs, and
 * each tab saves its choices under its own prefix. Before the split, the page
 * saved one set under "experiments-*". Each tab starts from that set, minus the
 * columns it does not show. Legacy keys are left in place so a rollback still
 * finds them, and a tab key that already has a value is never overwritten.
 */

import isArray from "lodash/isArray";

import {
  COLUMN_DATASET_VERSION_ID,
  EXPERIMENTS_TAB_STORAGE,
  ExperimentsTabStorage,
  isExcludedTabColumn,
} from "@/constants/experiments";
import { COLUMN_NAME_ID } from "@/types/shared";

import type { Migration } from "./engine";

const LEGACY_PREFIX = "experiments";
const LEGACY_PINNED_PREFIX = `${LEGACY_PREFIX}-pinned-experiments-`;

// Columns the tabs show that the pre-split page did not have.
const ADDED_COLUMN_IDS = [COLUMN_DATASET_VERSION_ID];

const readArray = (key: string): unknown[] | null => {
  const raw = localStorage.getItem(key);
  if (raw === null) return null;
  try {
    const value = JSON.parse(raw);
    return isArray(value) ? value : null;
  } catch {
    return null;
  }
};

const isString = (value: unknown): value is string => typeof value === "string";

const isSort = (value: unknown): value is { id: string } =>
  typeof value === "object" &&
  value !== null &&
  isString((value as { id?: unknown }).id);

const copyIfAbsent = (
  legacyKey: string,
  newKey: string,
  transform?: (value: unknown[]) => unknown[],
) => {
  if (localStorage.getItem(newKey) !== null) return;

  if (!transform) {
    const raw = localStorage.getItem(legacyKey);
    if (raw !== null) localStorage.setItem(newKey, raw);
    return;
  }

  // A corrupt or wrongly shaped value (e.g. `{}` where an array is expected)
  // is skipped, so that setting falls back to its default.
  const value = readArray(legacyKey);
  if (value === null) return;
  localStorage.setItem(newKey, JSON.stringify(transform(value)));
};

const migrateTab = ({
  storageKeyPrefix,
  excludedColumnIds,
}: ExperimentsTabStorage) => {
  const legacyKey = (suffix: string) => `${LEGACY_PREFIX}-${suffix}`;
  const newKey = (suffix: string) => `${storageKeyPrefix}-${suffix}`;
  const isKept = (id: string) => !isExcludedTabColumn(id, excludedColumnIds);
  const keepColumns = (columns: unknown[]) =>
    columns.filter(isString).filter(isKept);

  // Users who never changed columns after the v2 key was introduced only have
  // the v1 key, which the table read with Name added.
  const selectedLegacyKey =
    localStorage.getItem(legacyKey("selected-columns-v2")) !== null
      ? legacyKey("selected-columns-v2")
      : legacyKey("selected-columns");
  const addsName = selectedLegacyKey === legacyKey("selected-columns");
  copyIfAbsent(selectedLegacyKey, newKey("selected-columns-v2"), (columns) => [
    ...new Set([
      ...(addsName ? [COLUMN_NAME_ID] : []),
      ...keepColumns(columns),
      ...ADDED_COLUMN_IDS,
    ]),
  ]);

  // The saved order predates the added columns, which would otherwise land at
  // the far right; put them after Name, where the design has them.
  copyIfAbsent(legacyKey("columns-order"), newKey("columns-order"), (order) => {
    const kept = keepColumns(order).filter(
      (id) => !ADDED_COLUMN_IDS.includes(id),
    );
    kept.splice(kept.indexOf(COLUMN_NAME_ID) + 1, 0, ...ADDED_COLUMN_IDS);
    return kept;
  });

  // A sort on a column the tab does not show would keep the list sorted with
  // no header to clear it.
  copyIfAbsent(legacyKey("columns-sort"), newKey("columns-sort"), (sort) =>
    sort.filter(isSort).filter((column) => isKept(column.id)),
  );

  [
    "scores-columns-order",
    "columns-width",
    "columns-groups",
    "pagination-size",
  ].forEach((suffix) => copyIfAbsent(legacyKey(suffix), newKey(suffix)));

  // Pins are saved per project. Each tab gets all of them; the tab's pinned
  // fetch is filtered by evaluation method, so pins of the other type are
  // pruned from that tab on first load.
  Object.keys(localStorage)
    .filter((key) => key.startsWith(LEGACY_PINNED_PREFIX))
    .forEach((key) =>
      copyIfAbsent(
        key,
        `${storageKeyPrefix}-pinned-experiments-${key.slice(LEGACY_PINNED_PREFIX.length)}`,
      ),
    );
};

export const migration002: Migration = {
  id: "002_split_experiments_tabs",
  description:
    "Copy the pre-split Experiments page settings into the Test suites and Datasets tabs",
  run: () => Object.values(EXPERIMENTS_TAB_STORAGE).forEach(migrateTab),
};
