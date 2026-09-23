import { useMemo } from "react";
import get from "lodash/get";
import isArray from "lodash/isArray";
import isNil from "lodash/isNil";
import isPlainObject from "lodash/isPlainObject";

import { Span, Trace } from "@/types/traces";
import { MappingRow } from "./fieldMappingTypes";
import { FieldRowError } from "./useFieldMappings";

export const DEFERRED_KEY = "spans";
export const USAGE_KEY = "usage";

export type PreviewCell =
  | { kind: "value"; text: string }
  | { kind: "empty" }
  | { kind: "deferred" };

export type PreviewRow = {
  id: string;
  cells: Record<string, PreviewCell>;
};

type UseMappingPreviewParams = {
  fixedRows: MappingRow[];
  customRows: MappingRow[];
  managedRows: MappingRow[];
  rowErrors: Record<string, FieldRowError>;
  entities: Array<Trace | Span>;
};

export const isEmptyValue = (value: unknown, emptyObjectIsEmpty: boolean) => {
  if (isNil(value)) return true;
  if (isArray(value)) return value.length === 0;
  if (emptyObjectIsEmpty && isPlainObject(value)) {
    return Object.keys(value as object).length === 0;
  }
  return false;
};

const toCell = (value: unknown, emptyObjectIsEmpty = false): PreviewCell => {
  if (isEmptyValue(value, emptyObjectIsEmpty)) return { kind: "empty" };

  return {
    kind: "value",
    text: typeof value === "string" ? value : JSON.stringify(value),
  };
};

export const managedValue = (entity: Trace | Span, key: string): unknown => {
  if (key === "feedback_scores") {
    return entity.feedback_scores?.map((score) => ({
      name: score.name,
      ...(score.category_name && { category_name: score.category_name }),
      value: score.value,
      ...(score.reason && { reason: score.reason }),
      source: score.source,
    }));
  }

  if (key === "comments") {
    return entity.comments?.map((comment) => ({
      id: comment.id,
      text: comment.text,
    }));
  }

  return get(entity, key);
};

const useMappingPreview = ({
  fixedRows,
  customRows,
  managedRows,
  rowErrors,
  entities,
}: UseMappingPreviewParams) => {
  const columns = useMemo(() => {
    const mapped = [...fixedRows, ...customRows]
      .filter((row) => row.path && !rowErrors[row.id])
      .map((row) => row.name.trim());

    return [...mapped, ...managedRows.map((row) => row.name)];
  }, [fixedRows, customRows, managedRows, rowErrors]);

  const rows = useMemo<PreviewRow[]>(() => {
    const mapped = [...fixedRows, ...customRows].filter(
      (row) => row.path && !rowErrors[row.id],
    );

    return entities.map((entity, index) => ({
      id: entity.id ?? String(index),
      cells: {
        ...Object.fromEntries(
          mapped.map((row) => [
            row.name.trim(),
            toCell(get(entity, row.path!)),
          ]),
        ),
        ...Object.fromEntries(
          managedRows.map((row) => [
            row.name,
            row.name === DEFERRED_KEY
              ? ({ kind: "deferred" } as PreviewCell)
              : toCell(managedValue(entity, row.name), row.name === USAGE_KEY),
          ]),
        ),
      },
    }));
  }, [fixedRows, customRows, managedRows, rowErrors, entities]);

  return { columns, rows };
};

export default useMappingPreview;
