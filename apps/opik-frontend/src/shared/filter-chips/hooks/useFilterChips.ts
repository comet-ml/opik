import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import isEqual from "lodash/isEqual";
import omit from "lodash/omit";
import without from "lodash/without";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, useQueryParam } from "use-query-params";
import { Filter } from "@/types/filters";
import { chipsToFilters } from "@/shared/filter-chips/lib/chipsToFilters";
import { sanitizeFilters } from "@/shared/filter-chips/lib/sanitizeFilters";
import {
  ChipDefinition,
  ChipValue,
  ChipValueMap,
} from "@/shared/filter-chips/types";

interface UseFilterChipsArgs {
  tableId: string;
  urlKey: string;
  definitions: ChipDefinition[];
  defaultPinned: string[];
  onChange?: () => void;
}

interface UseFilterChipsResult {
  chipsPinned: ChipDefinition[];
  chipsUnpinned: ChipDefinition[];
  values: ChipValueMap;
  filters: Filter[];
  applyValue: (id: string, value: ChipValue) => void;
  clearValue: (id: string) => void;
  clearAll: () => void;
  pinChip: (id: string) => void;
  unpinChip: (id: string) => void;
  managerOpen: boolean;
  setManagerOpen: (open: boolean) => void;
  openChipId: string | null;
  setOpenChipId: (id: string | null) => void;
}

const EMPTY_VALUES: ChipValueMap = {};
const EMPTY_FILTERS: Filter[] = [];

const useFilterChips = ({
  tableId,
  urlKey,
  definitions,
  defaultPinned,
  onChange,
}: UseFilterChipsArgs): UseFilterChipsResult => {
  const [pinnedIds = defaultPinned, setPinnedIds] = useLocalStorageState<
    string[]
  >(`chips:pinnedConfig:${tableId}`, { defaultValue: defaultPinned });

  const [rawFilters, setRawFilters] = useQueryParam<Filter[] | undefined>(
    urlKey,
    JsonParam,
    { updateType: "replaceIn" },
  );

  const urlFilters: Filter[] = useMemo(
    () => (Array.isArray(rawFilters) ? rawFilters : EMPTY_FILTERS),
    [rawFilters],
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

  const [managerOpen, setManagerOpen] = useState(false);
  const [openChipId, setOpenChipId] = useState<string | null>(null);

  const chipsPinned = useMemo<ChipDefinition[]>(() => {
    const explicit = new Set(pinnedIds);
    const applied = new Set(Object.keys(values));
    return definitions.filter((d) => explicit.has(d.id) || applied.has(d.id));
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
    (updater: (prev: ChipValueMap) => ChipValueMap) => {
      setRawFilters((prevRaw) => {
        const prevValues = sanitizeFilters(
          Array.isArray(prevRaw) ? prevRaw : EMPTY_FILTERS,
          definitions,
        ).values;
        const nextFilters = chipsToFilters(definitions, updater(prevValues));
        return nextFilters.length > 0 ? nextFilters : undefined;
      });
    },
    [definitions, setRawFilters],
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
      writeValues((prev) =>
        isEqual(prev[id], value) ? prev : { ...prev, [id]: value },
      );
      setPinnedIds((prev = defaultPinned) =>
        prev.includes(id) ? prev : [...prev, id],
      );
    },
    [writeValues, setPinnedIds, defaultPinned],
  );

  const clearValue = useCallback(
    (id: string) => {
      writeValues((prev) => (id in prev ? omit(prev, id) : prev));
    },
    [writeValues],
  );

  const clearAll = useCallback(() => {
    writeValues(() => EMPTY_VALUES);
  }, [writeValues]);

  const pinChip = useCallback(
    (id: string) => {
      setPinnedIds((prev = defaultPinned) =>
        prev.includes(id) ? prev : [...prev, id],
      );
    },
    [setPinnedIds, defaultPinned],
  );

  const unpinChip = useCallback(
    (id: string) => {
      setPinnedIds((prev = defaultPinned) => without(prev, id));
      clearValue(id);
    },
    [setPinnedIds, clearValue, defaultPinned],
  );

  return {
    chipsPinned,
    chipsUnpinned,
    values,
    filters,
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
