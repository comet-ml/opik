import isEmpty from "lodash/isEmpty";
import last from "lodash/last";
import {
  Dispatch,
  SetStateAction,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { EnrichmentOptions } from "./useAddToDatasetForm";
import {
  FIXED_MAPPING_FIELDS,
  MAX_FIELD_NAME_LENGTH,
  MappingRow,
  QUICK_ADD_CHIPS,
  QuickAddChip,
} from "./fieldMappingTypes";

type UseFieldMappingsParams = {
  enrichmentOptions: EnrichmentOptions;
  setEnrichmentOptions: Dispatch<SetStateAction<EnrichmentOptions>>;
  hasOnlySpans: boolean;
  datasetColumns: string[];
};

export type FieldRowError = "blank" | "duplicate" | "too_long" | "no_path";

const nameFromPath = (path: string) =>
  last(path.split("."))?.replace(/\[.*\]$/, "") ?? "";

const toManagedRow = (chip: QuickAddChip): MappingRow => ({
  id: chip.option,
  name: chip.key,
  kind: "managed",
  option: chip.option,
});

const useFieldMappings = ({
  enrichmentOptions,
  setEnrichmentOptions,
  hasOnlySpans,
  datasetColumns,
}: UseFieldMappingsParams) => {
  const nextIdRef = useRef(0);
  const [fixedPaths, setFixedPaths] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      FIXED_MAPPING_FIELDS.map(({ key, defaultPath }) => [key, defaultPath]),
    ),
  );

  const fixedRows: MappingRow[] = useMemo(
    () =>
      FIXED_MAPPING_FIELDS.map(({ key }) => ({
        id: key,
        name: key,
        kind: "fixed" as const,
        path: fixedPaths[key],
      })),
    [fixedPaths],
  );

  const chips = useMemo(
    () => QUICK_ADD_CHIPS.filter((chip) => !(hasOnlySpans && chip.tracesOnly)),
    [hasOnlySpans],
  );

  const [additionalRows, setAdditionalRows] = useState<MappingRow[]>(() =>
    chips.filter((chip) => enrichmentOptions[chip.option]).map(toManagedRow),
  );

  // Enrichment options also change from the basic-mode checkboxes, so the rows
  // follow them here rather than only from setManagedOption.
  useEffect(() => {
    setAdditionalRows((prev) => {
      const enabled = chips.filter((chip) => enrichmentOptions[chip.option]);
      const enabledIds = new Set<string>(enabled.map((chip) => chip.option));
      const kept = prev.filter(
        (row) => row.kind === "custom" || enabledIds.has(row.id),
      );
      const present = new Set(kept.map((row) => row.id));
      const added = enabled
        .filter((chip) => !present.has(chip.option))
        .map(toManagedRow);

      if (added.length === 0 && kept.length === prev.length) return prev;
      return [...kept, ...added];
    });
  }, [chips, enrichmentOptions]);

  const customRows = useMemo(
    () => additionalRows.filter((row) => row.kind === "custom"),
    [additionalRows],
  );

  const managedRows = useMemo(
    () => additionalRows.filter((row) => row.kind === "managed"),
    [additionalRows],
  );

  const availableChips: QuickAddChip[] = useMemo(
    () => chips.filter((chip) => !enrichmentOptions[chip.option]),
    [chips, enrichmentOptions],
  );

  const datasetChips = useMemo(() => {
    const reserved = new Set([
      ...FIXED_MAPPING_FIELDS.map(({ key }) => key),
      ...QUICK_ADD_CHIPS.map((chip) => chip.key),
      ...customRows.map((row) => row.name.trim()),
    ]);

    return datasetColumns.filter((column) => !reserved.has(column));
  }, [datasetColumns, customRows]);

  const rowErrors = useMemo(() => {
    const taken = new Set([
      ...FIXED_MAPPING_FIELDS.map(({ key }) => key),
      ...managedRows.map((row) => row.name),
    ]);
    const errors: Record<string, FieldRowError> = {};

    FIXED_MAPPING_FIELDS.forEach(({ key }) => {
      if (!fixedPaths[key]?.trim()) {
        errors[key] = "no_path";
      }
    });

    customRows.forEach((row) => {
      const name = row.name.trim();
      if (!name) {
        errors[row.id] = "blank";
        return;
      }
      if (name.length > MAX_FIELD_NAME_LENGTH) {
        errors[row.id] = "too_long";
        return;
      }
      if (taken.has(name)) {
        errors[row.id] = "duplicate";
        return;
      }
      taken.add(name);

      if (!row.path?.trim()) {
        errors[row.id] = "no_path";
      }
    });

    return errors;
  }, [customRows, managedRows, fixedPaths]);

  const fieldMappings = useMemo(() => {
    const mappings: Record<string, string> = {};

    FIXED_MAPPING_FIELDS.forEach(({ key }) => {
      const path = fixedPaths[key]?.trim();
      if (path) {
        mappings[key] = path;
      }
    });

    customRows.forEach((row) => {
      const path = row.path?.trim();
      if (path && !rowErrors[row.id]) {
        mappings[row.name.trim()] = path;
      }
    });

    return mappings;
  }, [fixedPaths, customRows, rowErrors]);

  const canUseBasicMode = useMemo(
    () =>
      isEmpty(customRows) &&
      FIXED_MAPPING_FIELDS.every(
        ({ key, defaultPath }) => fixedPaths[key] === defaultPath,
      ),
    [customRows, fixedPaths],
  );

  const setFixedPath = useCallback((key: string, path: string) => {
    setFixedPaths((prev) => ({ ...prev, [key]: path }));
  }, []);

  const addRow = useCallback((path: string) => {
    nextIdRef.current += 1;
    const id = `custom-${nextIdRef.current}`;
    setAdditionalRows((prev) => [
      ...prev,
      {
        id,
        name: nameFromPath(path),
        kind: "custom",
        path,
        touched: true,
      },
    ]);
    return id;
  }, []);

  const addNamedRow = useCallback((name: string) => {
    nextIdRef.current += 1;
    const id = `custom-${nextIdRef.current}`;
    setAdditionalRows((prev) => [...prev, { id, name, kind: "custom" }]);
    return id;
  }, []);

  const removeRow = useCallback((id: string) => {
    setAdditionalRows((prev) => prev.filter((row) => row.id !== id));
  }, []);

  const renameRow = useCallback((id: string, name: string) => {
    setAdditionalRows((prev) =>
      prev.map((row) =>
        row.id === id ? { ...row, name, touched: true } : row,
      ),
    );
  }, []);

  const setRowPath = useCallback((id: string, path: string) => {
    setAdditionalRows((prev) =>
      prev.map((row) =>
        row.id === id ? { ...row, path, touched: true } : row,
      ),
    );
  }, []);

  const setManagedOption = useCallback(
    (option: keyof EnrichmentOptions, enabled: boolean) => {
      setEnrichmentOptions((prev) => ({ ...prev, [option]: enabled }));
    },
    [setEnrichmentOptions],
  );

  return {
    fixedRows,
    customRows,
    managedRows,
    additionalRows,
    availableChips,
    datasetChips,
    rowErrors,
    isValid: isEmpty(rowErrors),
    canUseBasicMode,
    fieldMappings,
    setFixedPath,
    addRow,
    addNamedRow,
    removeRow,
    renameRow,
    setRowPath,
    setManagedOption,
  };
};

export default useFieldMappings;
