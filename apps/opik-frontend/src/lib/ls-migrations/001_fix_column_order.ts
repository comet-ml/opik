/**
 * Migration 001: Fix name/id column order
 *
 * Context: Two changes caused the Name column to appear last for existing users:
 * 1. Name/ID unpin (cae8e5efea) moved Name from pinned columns into columnsOrder
 *    but didn't migrate existing stored orders.
 * 2. Sort fallback change (8cc71195df) changed `?? 0` to `?? Infinity`, so columns
 *    missing from stored order now appear last instead of first.
 *
 * This migration inserts "name" and/or "id" at the front of stored columnsOrder
 * arrays, but only if the column is currently visible (in selectedColumns).
 */

import includes from "lodash/includes";
import isArray from "lodash/isArray";
import isEmpty from "lodash/isEmpty";
import last from "lodash/last";
import without from "lodash/without";

import type { Migration } from "./engine";

// Tables where "name" was pinned — ensure "name" is near the front
const NAME_KEYS = [
  "projects",
  "workspace-annotation-queues",
  "annotation-queues",
  "workspace-rules",
  "project-rules",
  "datasets",
  "dashboards",
  "prompts",
  "alerts",
  "feedback-definitions",
  "experiments",
  "prompt-experiments",
  "optimization-experiments",
];

// Tables where "id" was pinned — ensure "id" is at position 0
const ID_KEYS = [
  "threads",
  "traces",
  "spans",
  "queue-trace",
  "queue-thread",
  "compare-experiments",
  "compare-trials",
];

function readJsonArray(key: string): string[] | null {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!isArray(parsed) || isEmpty(parsed)) return null;
    if (!parsed.every((item: unknown) => typeof item === "string")) return null;
    return parsed;
  } catch {
    return null;
  }
}

function isColumnVisible(column: string, prefix: string): boolean {
  const selected =
    readJsonArray(`${prefix}-selected-columns-v2`) ??
    readJsonArray(`${prefix}-selected-columns`);
  if (!selected) return false;
  return includes(selected, column);
}

function needsFix(order: string[], column: string): boolean {
  return !includes(order, column) || last(order) === column;
}

function fixColumn(
  prefix: string,
  column: string,
  insertAt: (order: string[]) => void,
): void {
  const order = readJsonArray(`${prefix}-columns-order`);
  if (!order) return;
  if (!isColumnVisible(column, prefix)) return;
  if (!needsFix(order, column)) return;

  const filtered = without(order, column);
  insertAt(filtered);
  localStorage.setItem(`${prefix}-columns-order`, JSON.stringify(filtered));
}

export const migration001: Migration = {
  id: "001_fix_column_order",
  description: "Fix name/id column order for existing users",
  run() {
    // Fix "id" first so "name" can insert after it
    for (const prefix of ID_KEYS) {
      fixColumn(prefix, "id", (order) => order.unshift("id"));
    }

    for (const prefix of NAME_KEYS) {
      fixColumn(prefix, "name", (order) => order.unshift("name"));
    }
  },
};
