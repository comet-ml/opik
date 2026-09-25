import { useMemo } from "react";
import first from "lodash/first";
import get from "lodash/get";

import { Span, Trace } from "@/types/traces";
import { MappingRow } from "./fieldMappingTypes";
import {
  DEFERRED_KEY,
  USAGE_KEY,
  isEmptyValue,
  managedValue,
} from "./useMappingPreview";

const PARSEABLE_ROOTS = ["input", "output", "metadata"];

export type FieldCoverage = { covered: number; total: number };

type UseFieldCoverageParams = {
  fixedRows: MappingRow[];
  customRows: MappingRow[];
  managedRows: MappingRow[];
  selectedEntities: Array<Trace | Span>;
  sampleEntities: Array<Trace | Span>;
};

type NormalizedEntity = {
  entity: Trace | Span;
  truncatedRoots: Set<string>;
};

const rootOf = (path: string) =>
  first(path.split("."))?.replace(/\[.*\]$/, "") ?? path;

const normalize = (entity: Trace | Span): NormalizedEntity => {
  const truncatedRoots = new Set<string>();
  let normalized = entity;

  PARSEABLE_ROOTS.forEach((key) => {
    const value = get(entity, key);
    if (typeof value !== "string") return;

    try {
      normalized = { ...normalized, [key]: JSON.parse(value) };
    } catch {
      truncatedRoots.add(key);
    }
  });

  return { entity: normalized, truncatedRoots };
};

const useFieldCoverage = ({
  fixedRows,
  customRows,
  managedRows,
  selectedEntities,
  sampleEntities,
}: UseFieldCoverageParams) => {
  const normalized = useMemo(
    () => selectedEntities.map(normalize),
    [selectedEntities],
  );

  return useMemo(() => {
    const coverage: Record<string, FieldCoverage> = {};
    if (normalized.length === 0) return coverage;

    const add = (id: string, path: string, isManaged: boolean) => {
      const root = rootOf(path);
      const isNested = path !== root;

      if (
        isNested &&
        normalized.some(({ truncatedRoots }) => truncatedRoots.has(root))
      ) {
        return;
      }

      const missingEverywhere = normalized.every(
        ({ entity }) => get(entity, root) === undefined,
      );
      const presentInSample = sampleEntities.some(
        (entity) => get(entity, root) !== undefined,
      );
      if (missingEverywhere && presentInSample) return;

      const covered = normalized.filter(
        ({ entity }) =>
          !isEmptyValue(
            isManaged ? managedValue(entity, path) : get(entity, path),
            path === USAGE_KEY,
          ),
      ).length;

      coverage[id] = { covered, total: normalized.length };
    };

    [...fixedRows, ...customRows].forEach((row) => {
      if (row.path) add(row.id, row.path, false);
    });

    managedRows.forEach((row) => {
      if (row.name !== DEFERRED_KEY) add(row.id, row.name, true);
    });

    return coverage;
  }, [fixedRows, customRows, managedRows, normalized, sampleEntities]);
};

export default useFieldCoverage;
