import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import compact from "lodash/compact";
import isEqual from "lodash/isEqual";
import keyBy from "lodash/keyBy";
import omit from "lodash/omit";
import uniq from "lodash/uniq";
import without from "lodash/without";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, useQueryParam } from "use-query-params";
import { Filter } from "@/types/filters";
import { chipsToFilters } from "@/shared/filter-chips/lib/chipsToFilters";
import { sanitizeFilters } from "@/shared/filter-chips/lib/sanitizeFilters";
import useFilterChipsAnalytics, {
  FilterRemovedSource,
} from "@/shared/filter-chips/hooks/useFilterChipsAnalytics";
import {
  ChipDefinition,
  ChipValue,
  ChipValueMap,
} from "@/shared/filter-chips/types";
import { createLocalStorageMemory } from "@/lib/localStorageMemory";

export type { FilterRemovedSource };

type WriteValuesAnalytics =
  | { kind: "apply"; id: string; value: ChipValue }
  | { kind: "remove"; id: string; source: FilterRemovedSource }
  | { kind: "clear_all" };

interface UseFilterChipsArgs {
  tableId: string;
  urlKey: string;
  definitions: ChipDefinition[];
  defaultPinned: string[];
  onChange?: () => void;
  persistKey?: string;
}

interface UseFilterChipsResult {
  chipsPinned: ChipDefinition[];
  chipsUnpinned: ChipDefinition[];
  values: ChipValueMap;
  filters: Filter[];
  filtersParamAbsent: boolean;
  applyValue: (id: string, value: ChipValue) => void;
  clearValue: (id: string, source?: FilterRemovedSource) => void;
  clearAll: () => void;
  pinChip: (id: string) => void;
  unpinChip: (id: string) => void;
  managerOpen: boolean;
  setManagerOpen: (open: boolean) => void;
  openChipId: string | null;
  setOpenChipId: (id: string | null) => void;
}

export const getPinnedChipsStorageKey = (tableId: string) =>
  `chips:pinnedConfig:${tableId}`;

const EMPTY_VALUES: ChipValueMap = {};
const EMPTY_FILTERS: Filter[] = [];

const useFilterChips = ({
  tableId,
  urlKey,
  definitions,
  defaultPinned,
  onChange,
  persistKey,
}: UseFilterChipsArgs): UseFilterChipsResult => {
  const [pinnedIds = defaultPinned, setPinnedIds] = useLocalStorageState<
    string[]
  >(getPinnedChipsStorageKey(tableId), { defaultValue: defaultPinned });

  const [rawFilters, setRawFilters] = useQueryParam<Filter[] | undefined>(
    urlKey,
    JsonParam,
    { updateType: "replaceIn" },
  );

  const memory = useMemo(
    () =>
      persistKey ? createLocalStorageMemory<Filter[]>(persistKey) : undefined,
    [persistKey],
  );

  // Computed during render so the first render is already filtered.
  const restored = useMemo(
    () => (rawFilters === undefined && memory ? memory.load() : undefined),
    [rawFilters, memory],
  );
  const filtersParamAbsent = rawFilters === undefined;
  const sourceFilters = rawFilters ?? restored;

  // Re-runs on every URL change: re-clicking the current page's link doesn't remount it.
  useEffect(() => {
    if (restored && restored.length > 0) setRawFilters(restored);
  }, [restored, setRawFilters]);

  const urlFilters: Filter[] = useMemo(
    () => (Array.isArray(sourceFilters) ? sourceFilters : EMPTY_FILTERS),
    [sourceFilters],
  );

  const { values, dropped } = useMemo(
    () => sanitizeFilters(urlFilters, definitions),
    [urlFilters, definitions],
  );

  useEffect(() => {
    if (dropped.length === 0) return;
    // eslint-disable-next-line no-console
    console.info(
      `[FilterChips] Sanitized ${urlFilters.length} filters from "${urlKey}": ${
        Object.keys(values).length
      } applied, ${dropped.length} dropped.`,
      { dropped },
    );
  }, [dropped, urlFilters.length, urlKey, values]);

  const [managerOpen, setManagerOpenState] = useState(false);
  const [openChipId, setOpenChipId] = useState<string | null>(null);

  const analytics = useFilterChipsAnalytics({
    tableId,
    definitions,
    values,
    pinnedIds,
  });

  const chipsPinned = useMemo<ChipDefinition[]>(() => {
    const defById = keyBy(definitions, "id");
    const orderedIds = uniq([...pinnedIds, ...Object.keys(values)]);
    return compact(orderedIds.map((id) => defById[id]));
  }, [pinnedIds, values, definitions]);

  const chipsUnpinned = useMemo<ChipDefinition[]>(() => {
    const explicit = new Set(pinnedIds);
    const applied = new Set(Object.keys(values));
    return definitions.filter((d) => !explicit.has(d.id) && !applied.has(d.id));
  }, [pinnedIds, values, definitions]);

  const filters = useMemo(
    () => chipsToFilters(definitions, values),
    [definitions, values],
  );

  const writeValues = useCallback(
    (
      updater: (prev: ChipValueMap) => ChipValueMap,
      track?: WriteValuesAnalytics,
    ) => {
      setRawFilters((prevRaw) => {
        // An edit before the restore effect has run must build on the remembered filters.
        const prevFilters = Array.isArray(prevRaw)
          ? prevRaw
          : memory?.load() ?? EMPTY_FILTERS;
        const prevValues = sanitizeFilters(prevFilters, definitions).values;
        const nextValues = updater(prevValues);

        switch (track?.kind) {
          case "apply":
            if (!isEqual(prevValues[track.id], track.value)) {
              analytics.trackApplied(track.id, track.value);
            }
            break;
          case "remove":
            if (track.id in prevValues) {
              analytics.trackRemoved(track.id, track.source);
            }
            break;
          case "clear_all":
            for (const id of Object.keys(prevValues)) {
              analytics.trackRemoved(id, "clear_all");
            }
            break;
        }

        const nextFilters = chipsToFilters(definitions, nextValues);
        const next = nextFilters.length > 0 ? nextFilters : undefined;
        memory?.save(next);
        return next;
      });
    },
    [definitions, setRawFilters, analytics, memory],
  );

  const previousFiltersRef = useRef(filters);
  useEffect(() => {
    if (!isEqual(previousFiltersRef.current, filters)) {
      previousFiltersRef.current = filters;
      onChange?.();
    }
  }, [filters, onChange]);

  const applyValue = useCallback(
    (id: string, value: ChipValue) => {
      writeValues(
        (prev) => (isEqual(prev[id], value) ? prev : { ...prev, [id]: value }),
        { kind: "apply", id, value },
      );
      setPinnedIds((prev = defaultPinned) =>
        prev.includes(id) ? prev : [...prev, id],
      );
    },
    [writeValues, setPinnedIds, defaultPinned],
  );

  const clearValue = useCallback(
    (id: string, source: FilterRemovedSource = "dialog") => {
      writeValues((prev) => (id in prev ? omit(prev, id) : prev), {
        kind: "remove",
        id,
        source,
      });
    },
    [writeValues],
  );

  const clearAll = useCallback(() => {
    writeValues(() => EMPTY_VALUES, { kind: "clear_all" });
  }, [writeValues]);

  const pinChip = useCallback(
    (id: string) => {
      analytics.trackPinned(id);
      setPinnedIds((prev = defaultPinned) =>
        prev.includes(id) ? prev : [...prev, id],
      );
    },
    [setPinnedIds, defaultPinned, analytics],
  );

  const unpinChip = useCallback(
    (id: string) => {
      analytics.trackUnpinned(id);
      setPinnedIds((prev = defaultPinned) => without(prev, id));
      writeValues((prev) => (id in prev ? omit(prev, id) : prev), {
        kind: "remove",
        id,
        source: "unpin",
      });
    },
    [setPinnedIds, writeValues, defaultPinned, analytics],
  );

  const setManagerOpen = useCallback(
    (open: boolean) => {
      analytics.trackManagerOpenChange(open);
      setManagerOpenState(open);
    },
    [analytics],
  );

  return {
    chipsPinned,
    chipsUnpinned,
    values,
    filters,
    filtersParamAbsent,
    applyValue,
    clearValue,
    clearAll,
    pinChip,
    unpinChip,
    managerOpen,
    setManagerOpen,
    openChipId,
    setOpenChipId,
  };
};

export default useFilterChips;
