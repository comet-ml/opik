import groupBy from "lodash/groupBy";
import isEqual from "lodash/isEqual";
import { COLUMN_TYPE } from "@/types/shared";
import { Filter, FilterOperator } from "@/types/filters";
import { AnnotationQueueAutomationFilter } from "@/types/annotation-queues";
import { durationToSeconds, processFiltersArray } from "@/lib/filters";
import { chipsToFilters } from "@/shared/filter-chips/lib/chipsToFilters";
import { sanitizeFilters } from "@/shared/filter-chips/lib/sanitizeFilters";
import { isRowApplied } from "@/shared/filter-chips/chips/QueryBuilderChip/QueryBuilderChipPopoverContent";
import {
  ChipDefinition,
  ChipValueMap,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";

export const AUTOMATION_EXCLUDED_CHIP_IDS = [
  "id",
  "thread_id",
  "annotation_queue_ids",
];

export const toAutomationChipDefinitions = (definitions: ChipDefinition[]) =>
  definitions.filter((def) => !AUTOMATION_EXCLUDED_CHIP_IDS.includes(def.id));

/**
 * Block values to the filters the API stores, processed exactly as the Logs page processes them before
 * querying, so an automation matches what the same filters show there.
 */
export const toAutomationFilters = (
  definitions: ChipDefinition[],
  values: ChipValueMap,
): AnnotationQueueAutomationFilter[] =>
  processFiltersArray(chipsToFilters(definitions, values)).map(
    ({ field, operator, key, value }) => ({
      field,
      operator: operator as FilterOperator,
      ...(key ? { key } : {}),
      value: String(value),
    }),
  );

const columnTypeOf = (definition: ChipDefinition): COLUMN_TYPE | "" =>
  ("columnType" in definition ? definition.columnType : undefined) ?? "";

/**
 * Blocks that were added but hold no complete filter: nothing filled in yet, or a row filled in only partly.
 * Saving would drop them without a word, so the dialog asks for each to be completed or removed. Blank rows
 * added to a block that has complete ones are dropped as the Logs filters drop them.
 */
export const incompleteAutomationBlocks = (
  definitions: ChipDefinition[],
  blocks: { ids: string[]; values: ChipValueMap },
): string[] => {
  const definitionsById = new Map(definitions.map((def) => [def.id, def]));
  return blocks.ids.filter((id) => {
    const definition = definitionsById.get(id);
    if (!definition) return false;

    const blockValue = blocks.values[id];
    if (!blockValue) return true;
    if (definition.kind !== "query-builder") return false;

    const applied = isRowApplied(
      Boolean(definition.key),
      definition.key?.validate,
      definition.value?.validate,
    );
    return (blockValue as QueryBuilderChipValue).rows.some(
      (row) =>
        !applied(row) &&
        (Boolean(row.key) || (row.value !== undefined && row.value !== "")),
    );
  });
};

const filterKey = ({
  field,
  operator,
  key,
  value,
}: AnnotationQueueAutomationFilter) =>
  JSON.stringify([field, operator, key ?? "", String(value)]);

const sameFilters = (
  a: AnnotationQueueAutomationFilter[],
  b: AnnotationQueueAutomationFilter[],
) =>
  a.length === b.length &&
  isEqual(a.map(filterKey).sort(), b.map(filterKey).sort());

/**
 * Splits stored filters into those the dialog can show as blocks and those it can only keep. A field's filters are
 * shown only if they come back from the blocks exactly as stored; anything a block would drop or change (a field
 * with no block here, such as guardrails while guardrails are off or a field only the API offers, an operator the
 * block does not offer, a value it rejects) is kept and sent back unchanged, rather than lost or rewritten on save,
 * which would change what the automation matches.
 */
export const splitAutomationFilters = (
  definitions: ChipDefinition[],
  filters: AnnotationQueueAutomationFilter[] = [],
): {
  shown: AnnotationQueueAutomationFilter[];
  kept: AnnotationQueueAutomationFilter[];
} => {
  const shown: AnnotationQueueAutomationFilter[] = [];
  const kept: AnnotationQueueAutomationFilter[] = [];
  Object.values(groupBy(filters, (filter) => filter.field)).forEach((group) => {
    const roundTrip = toAutomationFilters(
      definitions,
      fromAutomationFilters(definitions, group),
    );
    (sameFilters(roundTrip, group) ? shown : kept).push(...group);
  });
  return { shown, kept };
};

/**
 * Stored filters back to block values. Durations are stored in milliseconds, as the backend reads them,
 * and edited in seconds, as the Logs page shows them; time filters survive the round trip as they are.
 */
export const fromAutomationFilters = (
  definitions: ChipDefinition[],
  filters: AnnotationQueueAutomationFilter[] = [],
): ChipValueMap => {
  const typeByField = new Map(
    definitions.map((def) => [def.field, columnTypeOf(def)]),
  );

  const asFilters: Filter[] = filters.map((filter, index) => {
    const type = typeByField.get(filter.field) ?? "";
    return {
      id: `automation-filter-${index}`,
      field: filter.field,
      type,
      operator: filter.operator,
      key: filter.key ?? "",
      value:
        type === COLUMN_TYPE.duration
          ? durationToSeconds(filter.value)
          : filter.value,
    };
  });

  return sanitizeFilters(asFilters, definitions).values;
};
