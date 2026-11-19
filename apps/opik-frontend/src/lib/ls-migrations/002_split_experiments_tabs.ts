/**
 * Migration 002: Split the Experiments page settings per tab
 *
 * Context: The Experiments page split into Test suites and Datasets tabs, and
 * each tab saves its choices under its own prefix. Before the split, the page
 * saved one set under "experiments-*". Each tab starts from that set, minus the
 * columns it does not show. A tab key that already has a value is not
 * overwritten.
 *
 * Legacy keys are left in place so a rollback still finds them. A snapshot of
 * what was copied is kept, and `reconcileSplitExperimentsTabs` runs on every
 * load: when an older version changed a legacy setting during a rollback, that
 * setting is copied again, so the change is not lost on the next upgrade.
 */

import isArray from "lodash/isArray";
import isPlainObject from "lodash/isPlainObject";
import isString from "lodash/isString";

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
const SNAPSHOT_KEY = "opik-ls-migrations-002-snapshot";

// Columns the tabs show that the pre-split page did not have.
const ADDED_COLUMN_IDS = [COLUMN_DATASET_VERSION_ID];

type ColumnSortLike = { id: string };

type Setting = {
  // Legacy keys the value is built from; a change in any of them re-copies it.
  sources: string[];
  target: string;
  // Returns the value to store, or null to leave the tab on its default.
  build: () => string | null;
};

const legacyKey = (suffix: string) => `${LEGACY_PREFIX}-${suffix}`;

const isSort = (value: unknown): value is ColumnSortLike =>
  isPlainObject(value) && isString((value as { id?: unknown }).id);

// A corrupt or wrongly shaped value (`{}` where a list is expected, or a list
// with an invalid member such as `[null]`) is skipped as a whole, so that
// setting falls back to its default.
const readList = <T>(
  key: string,
  isItem: (value: unknown) => value is T,
): T[] | null => {
  const raw = localStorage.getItem(key);
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return isArray(value) && value.every(isItem) ? value : null;
  } catch {
    return null;
  }
};

const getTabSettings = ({
  storageKeyPrefix,
  excludedColumnIds,
}: ExperimentsTabStorage): Setting[] => {
  const newKey = (suffix: string) => `${storageKeyPrefix}-${suffix}`;
  const isKept = (id: string) => !isExcludedTabColumn(id, excludedColumnIds);

  const selectedColumns: Setting = {
    sources: [legacyKey("selected-columns-v2"), legacyKey("selected-columns")],
    target: newKey("selected-columns-v2"),
    build: () => {
      // Users who never changed columns after the v2 key was introduced only
      // have the v1 key, which the table read with Name added.
      const v2 = readList(legacyKey("selected-columns-v2"), isString);
      const hasV2 =
        localStorage.getItem(legacyKey("selected-columns-v2")) !== null;
      const columns = hasV2
        ? v2
        : readList(legacyKey("selected-columns"), isString);
      if (columns === null) return null;
      return JSON.stringify([
        ...new Set([
          ...(hasV2 ? [] : [COLUMN_NAME_ID]),
          ...columns.filter(isKept),
          ...ADDED_COLUMN_IDS,
        ]),
      ]);
    },
  };

  // The saved order predates the added columns, which would otherwise land at
  // the far right; put them after Name, where the design has them. When Name
  // is not in the saved order, they go at the end.
  const columnsOrder: Setting = {
    sources: [legacyKey("columns-order")],
    target: newKey("columns-order"),
    build: () => {
      const order = readList(legacyKey("columns-order"), isString);
      if (order === null) return null;
      const kept = order.filter(
        (id) => isKept(id) && !ADDED_COLUMN_IDS.includes(id),
      );
      const nameIndex = kept.indexOf(COLUMN_NAME_ID);
      kept.splice(
        nameIndex === -1 ? kept.length : nameIndex + 1,
        0,
        ...ADDED_COLUMN_IDS,
      );
      return JSON.stringify(kept);
    },
  };

  // A sort on a column the tab does not show would keep the list sorted with
  // no header to clear it.
  const columnsSort: Setting = {
    sources: [legacyKey("columns-sort")],
    target: newKey("columns-sort"),
    build: () => {
      const sort = readList(legacyKey("columns-sort"), isSort);
      return sort === null
        ? null
        : JSON.stringify(sort.filter((column) => isKept(column.id)));
    },
  };

  const copy = (source: string, target: string): Setting => ({
    sources: [source],
    target,
    build: () => localStorage.getItem(source),
  });

  const plainCopies = [
    "scores-columns-order",
    "columns-width",
    "columns-groups",
    "pagination-size",
  ].map((suffix) => copy(legacyKey(suffix), newKey(suffix)));

  // Pins are saved per project. Each tab gets all of them; the tab's pinned
  // fetch is filtered by evaluation method, so pins of the other type are
  // pruned from that tab on first load.
  const pins = Object.keys(localStorage)
    .filter((key) => key.startsWith(LEGACY_PINNED_PREFIX))
    .map((key) =>
      copy(
        key,
        newKey(`pinned-experiments-${key.slice(LEGACY_PINNED_PREFIX.length)}`),
      ),
    );

  return [selectedColumns, columnsOrder, columnsSort, ...plainCopies, ...pins];
};

const readSnapshot = (): Record<string, string> | null => {
  try {
    const value: unknown = JSON.parse(
      localStorage.getItem(SNAPSHOT_KEY) ?? "null",
    );
    return isPlainObject(value) ? (value as Record<string, string>) : null;
  } catch {
    return null;
  }
};

const sync = (snapshot: Record<string, string> | null) => {
  const nextSnapshot: Record<string, string> = {};

  Object.values(EXPERIMENTS_TAB_STORAGE)
    .flatMap(getTabSettings)
    .forEach(({ sources, target, build }) => {
      const values = sources.map((key) => localStorage.getItem(key));
      const fingerprint = JSON.stringify(values);
      nextSnapshot[target] = fingerprint;

      const shouldWrite = snapshot
        ? // Reconcile: an older version changed the legacy setting since the
          // last copy, so its value is the newest choice.
          fingerprint !== snapshot[target] &&
          values.some((value) => value !== null)
        : // First run: never overwrite a choice already saved for the tab.
          localStorage.getItem(target) === null;
      if (!shouldWrite) return;

      const value = build();
      if (value !== null) localStorage.setItem(target, value);
    });

  localStorage.setItem(SNAPSHOT_KEY, JSON.stringify(nextSnapshot));
};

export const migration002: Migration = {
  id: "002_split_experiments_tabs",
  description:
    "Copy the pre-split Experiments page settings into the Test suites and Datasets tabs",
  run: () => sync(null),
};

// Runs on every load, after the migrations. A no-op until migration 002 has
// run, and for every setting the legacy page has not changed since.
export const reconcileSplitExperimentsTabs = () => {
  const snapshot = readSnapshot();
  if (snapshot) sync(snapshot);
};
