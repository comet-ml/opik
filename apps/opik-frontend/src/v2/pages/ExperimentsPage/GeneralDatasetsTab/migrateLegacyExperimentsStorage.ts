import { ColumnSort } from "@tanstack/react-table";
import { COLUMN_NAME_ID } from "@/types/shared";

const LEGACY_PREFIX = "experiments";
const LEGACY_PINNED_PREFIX = `${LEGACY_PREFIX}-pinned-experiments-`;

type MigrationConfig = {
  storageKeyPrefix: string;
  excludedColumnIds: string[];
  addedColumnIds: string[];
};

const copyIfAbsent = <T>(
  legacyKey: string,
  newKey: string,
  transform: (value: T) => T = (value) => value,
) => {
  if (localStorage.getItem(newKey) !== null) return;
  const raw = localStorage.getItem(legacyKey);
  if (raw === null) return;

  let value: T;
  try {
    value = JSON.parse(raw);
  } catch {
    // A corrupt legacy value must not break the page; the tab falls back to its defaults.
    return;
  }
  localStorage.setItem(newKey, JSON.stringify(transform(value)));
};

// Before the Test suites / Datasets split, the page saved one set of choices
// under the "experiments" prefix. Each tab starts from that set, minus the
// columns it does not show. Legacy keys are left in place so a rollback still
// finds them.
export const migrateLegacyExperimentsStorage = ({
  storageKeyPrefix,
  excludedColumnIds,
  addedColumnIds,
}: MigrationConfig) => {
  const legacyKey = (suffix: string) => `${LEGACY_PREFIX}-${suffix}`;
  const newKey = (suffix: string) => `${storageKeyPrefix}-${suffix}`;
  const isKept = (id: string) => !excludedColumnIds.includes(id);

  copyIfAbsent<string[]>(
    legacyKey("selected-columns-v2"),
    newKey("selected-columns-v2"),
    (columns) => [...new Set([...columns.filter(isKept), ...addedColumnIds])],
  );

  // The saved order predates the added columns, which would otherwise land at
  // the far right; put them after Name, where the design has them.
  copyIfAbsent<string[]>(
    legacyKey("columns-order"),
    newKey("columns-order"),
    (order) => {
      const kept = order.filter(
        (id) => isKept(id) && !addedColumnIds.includes(id),
      );
      const nameIndex = kept.indexOf(COLUMN_NAME_ID);
      kept.splice(nameIndex + 1, 0, ...addedColumnIds);
      return kept;
    },
  );

  copyIfAbsent<ColumnSort[]>(
    legacyKey("columns-sort"),
    newKey("columns-sort"),
    (sort) => sort.filter((column) => isKept(column.id)),
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
