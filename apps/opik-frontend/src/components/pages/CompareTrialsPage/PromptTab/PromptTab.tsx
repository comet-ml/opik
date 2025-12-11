import React, { useMemo } from "react";
import { BooleanParam, StringParam, useQueryParam } from "use-query-params";
import { ColumnPinningState } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import isObject from "lodash/isObject";
import toLower from "lodash/toLower";
import find from "lodash/find";
import get from "lodash/get";
import isEqual from "lodash/isEqual";

import { CellContext } from "@tanstack/react-table";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import CompareExperimentsHeader from "@/components/pages-shared/experiments/CompareExperimentsHeader/CompareExperimentsHeader";
import CompareExperimentsConfigCell, {
  CompareConfig,
  CompareFiledValue,
} from "@/components/pages-shared/experiments/CompareExperimentsConfigCell/CompareExperimentsConfigCell";
import ComparePromptCell, {
  ComparePromptConfig,
  ComparePromptData,
} from "./ComparePromptCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Experiment } from "@/types/datasets";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import {
  OPTIMIZATION_EXAMPLES_KEY,
  OPTIMIZATION_PROMPT_KEY,
} from "@/constants/experiments";
import { toString } from "@/lib/utils";
import { extractPromptData, OpenAIMessage } from "@/lib/prompt";

const COLUMNS_WIDTH_KEY = "compare-trials-prompt-columns-width";

const PROMPT_KEY_PREFIX = "Prompt";
const EXAMPLES_KEY = "Examples";

type PromptDisplayResult =
  | Record<string, OpenAIMessage[]>
  | { [key: string]: unknown };

const extractPromptForDisplay = (promptData: unknown): PromptDisplayResult => {
  const extracted = extractPromptData(promptData);

  if (!extracted) {
    return { [PROMPT_KEY_PREFIX]: promptData };
  }

  if (extracted.type === "single") {
    return { [PROMPT_KEY_PREFIX]: extracted.data };
  }

  const result: Record<string, OpenAIMessage[]> = {};
  for (const [name, messages] of Object.entries(extracted.data)) {
    result[`${PROMPT_KEY_PREFIX}: ${name}`] = messages;
  }
  return result;
};

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["name"],
  right: [],
};

// Union type for rows - can be prompt or config
type PromptTabRow = (CompareConfig | ComparePromptConfig) & {
  rowType: "prompt" | "config";
};

export const DEFAULT_COLUMNS: ColumnData<PromptTabRow>[] = [
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
];

export type PromptTabProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
};

const PromptTab: React.FunctionComponent<PromptTabProps> = ({
  experimentsIds,
  experiments,
  isPending,
}) => {
  const [search = "", setSearch] = useQueryParam("searchConfig", StringParam, {
    updateType: "replaceIn",
  });

  const [onlyDiff = false, setOnlyDiff] = useQueryParam("diff", BooleanParam, {
    updateType: "replaceIn",
  });

  const isCompare = experimentsIds.length > 1;

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<PromptTabRow, PromptTabRow>(
      DEFAULT_COLUMNS,
      {},
    );

    experimentsIds.forEach((id: string) => {
      retVal.push({
        accessorKey: id,
        header: CompareExperimentsHeader as never,
        cell: (context) => {
          const row = context.row.original;
          // Type assertion is necessary here because TanStack Table's CellContext
          // cannot be narrowed based on the row's discriminant field (rowType).
          // The rowType check ensures type safety at runtime.
          if (row.rowType === "prompt") {
            return (
              <ComparePromptCell
                {...(context as unknown as CellContext<
                  ComparePromptConfig,
                  unknown
                >)}
              />
            );
          }
          return (
            <CompareExperimentsConfigCell
              {...(context as unknown as CellContext<CompareConfig, unknown>)}
            />
          );
        },
        meta: {
          custom: {
            onlyDiff,
            experiment: find(experiments, (e) => e.id === id),
          },
        },
        size: 400,
        minSize: 120,
      });
    });

    return retVal;
  }, [experimentsIds, onlyDiff, experiments]);

  const flattenExperimentMetadataMap = useMemo(() => {
    return experiments.reduce<
      Record<string, Record<string, CompareFiledValue | ComparePromptData>>
    >((acc, experiment) => {
      const promptData = get(
        experiment.metadata ?? {},
        OPTIMIZATION_PROMPT_KEY,
        "-",
      );
      const promptFormatted = extractPromptForDisplay(promptData);

      const examplesData = get(
        experiment.metadata ?? {},
        OPTIMIZATION_EXAMPLES_KEY,
        "-",
      );
      const examples = isObject(examplesData)
        ? JSON.stringify(examplesData, null, 2)
        : toString(examplesData);

      acc[experiment.id] = {
        ...promptFormatted,
        [EXAMPLES_KEY]: examples,
      };

      return acc;
    }, {});
  }, [experiments]);

  const allKeys = useMemo(() => {
    const keysSet = new Set<string>();
    Object.values(flattenExperimentMetadataMap).forEach((map) => {
      Object.keys(map).forEach((key) => keysSet.add(key));
    });
    const keys = Array.from(keysSet);
    const promptKeys = keys
      .filter((k) => k.startsWith(PROMPT_KEY_PREFIX))
      .sort();
    const otherKeys = keys.filter((k) => !k.startsWith(PROMPT_KEY_PREFIX));
    return [...promptKeys, ...otherKeys];
  }, [flattenExperimentMetadataMap]);

  const rows = useMemo(() => {
    return allKeys.map((key) => {
      const isPromptRow = key.startsWith(PROMPT_KEY_PREFIX);
      const data = experimentsIds.reduce<
        Record<string, CompareFiledValue | ComparePromptData>
      >((acc, id: string) => {
        acc[id] = flattenExperimentMetadataMap[id]?.[key] ?? undefined;
        return acc;
      }, {});
      const values = Object.values(data);

      const different = isPromptRow
        ? !values.every((v) => isEqual(values[0], v))
        : !values.every((v) => values[0] === v);

      return {
        name: key,
        base: experimentsIds[0],
        data,
        different,
        rowType: isPromptRow ? "prompt" : "config",
      } as PromptTabRow;
    });
  }, [flattenExperimentMetadataMap, experimentsIds, allKeys]);

  const filteredRows = useMemo(() => {
    return rows.filter((row) => {
      if (isCompare && onlyDiff && !row.different) {
        return false;
      }

      return !(search && !toLower(row.name).includes(toLower(search)));
    });
  }, [rows, search, onlyDiff, isCompare]);

  const noDataText = search
    ? "No search results"
    : isCompare
      ? "These trials have no prompt"
      : "This trial has no prompt";

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
        </div>
        <div className="flex items-center gap-2">
          {isCompare && (
            <>
              <Separator orientation="vertical" className="mx-2 h-4" />
              <div className="flex items-center space-x-2">
                <Label htmlFor="show-doff-only">Show differences only</Label>
                <Switch
                  id="show-doff-only"
                  onCheckedChange={setOnlyDiff}
                  checked={onlyDiff as boolean}
                />
              </div>
            </>
          )}
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={filteredRows}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        stickyHeader
      />
      <PageBodyStickyContainer
        className="pb-6"
        direction="horizontal"
        limitWidth
      />
    </>
  );
};

export default PromptTab;
