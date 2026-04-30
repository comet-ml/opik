import React, { useMemo } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { FoldVertical, UnfoldVertical } from "lucide-react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  OnChangeFn,
} from "@/types/shared";
import { Filters } from "@/types/filters";
import { BASE_TRACE_DATA_TYPE, Span, Trace } from "@/types/traces";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import ExpandableSearchInput from "@/shared/ExpandableSearchInput/ExpandableSearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import SelectBox, { SelectBoxProps } from "@/shared/SelectBox/SelectBox";
import { Skeleton } from "@/ui/skeleton";
import SpanDetailsButton from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/SpanDetailsButton";
import useTreeDetailsStore, {
  TreeNodeConfig,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import AddToDropdown from "@/v2/pages-shared/traces/AddToDropdown/AddToDropdown";
import {
  DetailsActionSection,
  DetailsActionSectionToggle,
  DetailsActionSectionValue,
  ButtonLayoutSize,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import { isObjectSpan } from "@/lib/traces";
import { TREE_FILTER_COLUMNS } from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/helpers";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { GuardrailResult } from "@/types/guardrails";
import { getJSONPaths } from "@/lib/utils";
import { getSpanTypeFilterConfig } from "@/v2/pages-shared/traces/spanTypeFilter";
import { usePermissions } from "@/contexts/PermissionsContext";

// Left toolbar — sits above the tree panel
type TraceTreeToolbarProps = {
  spanCount: number;
  search?: string;
  setSearch: OnChangeFn<string | undefined>;
  filters: Filters;
  setFilters: OnChangeFn<Filters>;
  isSpansLazyLoading: boolean;
  treeData: Array<Trace | Span>;
  config: TreeNodeConfig;
  setConfig: OnChangeFn<TreeNodeConfig>;
};

export const TraceTreeToolbar: React.FC<TraceTreeToolbarProps> = ({
  spanCount,
  search,
  setSearch,
  filters,
  setFilters,
  isSpansLazyLoading,
  treeData,
  config,
  setConfig,
}) => {
  const { toggleExpandAll, expandedTreeRows, fullExpandedSet } =
    useTreeDetailsStore();
  const isAllExpanded = expandedTreeRows.size === fullExpandedSet.size;

  const hasSearch = Boolean(search && search.length);
  const hasFilter = Boolean(filters.length);
  const hasSearchOrFilter = hasSearch || hasFilter;

  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const filtersColumnData = useMemo(() => {
    return [
      ...TREE_FILTER_COLUMNS,
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              type: COLUMN_TYPE.category,
            },
          ]
        : []),
    ];
  }, [isGuardrailsEnabled]);

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        ...getSpanTypeFilterConfig(isGuardrailsEnabled),
        [COLUMN_METADATA_ID]: {
          keyComponent: (
            props: {
              onValueChange: SelectBoxProps<string>["onChange"];
            } & SelectBoxProps<string>,
          ) => <SelectBox {...props} onChange={props.onValueChange} />,
          keyComponentProps: {
            options: uniq(
              treeData.reduce<string[]>((acc, d) => {
                return acc.concat(
                  isObject(d.metadata) || isArray(d.metadata)
                    ? getJSONPaths(d.metadata, "metadata").map((path) =>
                        path.substring(path.indexOf(".") + 1),
                      )
                    : [],
                );
              }, []),
            )
              .sort()
              .map((key) => ({ value: key, label: key })),
            placeholder: "key",
          },
        },
        [COLUMN_CUSTOM_ID]: {
          keyComponent: (
            props: {
              onValueChange: SelectBoxProps<string>["onChange"];
            } & SelectBoxProps<string>,
          ) => <SelectBox {...props} onChange={props.onValueChange} />,
          keyComponentProps: {
            options: uniq(
              treeData.reduce<string[]>((acc, d) => {
                return acc.concat(
                  (["input", "output"] as const).reduce<string[]>(
                    (internalAcc, key) =>
                      internalAcc.concat(
                        isObject(d[key]) || isArray(d[key])
                          ? getJSONPaths(d[key], key).map((path) => path)
                          : [],
                      ),
                    [],
                  ),
                );
              }, []),
            )
              .sort()
              .map((key) => ({ value: key, label: key })),
            placeholder: "key",
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: (
            props: {
              onValueChange: SelectBoxProps<string>["onChange"];
            } & SelectBoxProps<string>,
          ) => <SelectBox {...props} onChange={props.onValueChange} />,
          keyComponentProps: {
            options: uniq(
              treeData.reduce<string[]>((acc, d) => {
                return acc.concat(
                  isArray(d.feedback_scores)
                    ? d.feedback_scores.map((score) => score.name)
                    : [],
                );
              }, []),
            )
              .sort()
              .map((key) => ({ value: key, label: key })),
            placeholder: "Select score",
          },
        },
        [COLUMN_GUARDRAILS_ID]: {
          keyComponentProps: {
            options: [
              { value: GuardrailResult.FAILED, label: "Failed" },
              { value: GuardrailResult.PASSED, label: "Passed" },
            ],
            placeholder: "Status",
          },
        },
      },
    }),
    [isGuardrailsEnabled, treeData],
  );

  return (
    <div className="flex h-10 shrink-0 items-center border-b bg-muted/50 px-4">
      <div className="relative flex flex-1 items-center">
        <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
          Spans ({spanCount})
        </span>
        <div className="flex-auto" />
        <ExpandableSearchInput
          value={search}
          placeholder="Search by all fields"
          onChange={setSearch}
          disabled={isSpansLazyLoading}
          buttonVariant="ghost"
          tooltip="Search spans"
          overlayExpand
        />
      </div>
      <div className="flex items-center gap-1 text-foreground">
        <FiltersButton
          columns={filtersColumnData}
          filters={filters}
          onChange={setFilters}
          config={filtersConfig as never}
          layout="icon"
          variant="ghost"
          disabled={isSpansLazyLoading}
          align="start"
          tooltip="Filter spans"
        />
        <Separator orientation="vertical" className="mx-0.5 h-3" />
        {!hasSearchOrFilter ? (
          <>
            <TooltipWrapper
              content={isAllExpanded ? "Collapse all" : "Expand all"}
            >
              <Button onClick={toggleExpandAll} variant="ghost" size="icon-2xs">
                {isAllExpanded ? (
                  <FoldVertical className="size-3" />
                ) : (
                  <UnfoldVertical className="size-3" />
                )}
              </Button>
            </TooltipWrapper>
            <SpanDetailsButton config={config} onConfigChange={setConfig} />
          </>
        ) : (
          <Button
            variant="ghost"
            size="2xs"
            onClick={() => {
              setSearch(undefined);
              setFilters([]);
            }}
          >
            Clear
          </Button>
        )}
      </div>
    </div>
  );
};

// Right toolbar — sits above the data panel
type TraceDataToolbarProps = {
  dataToView: Trace | Span | undefined;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  isLoading?: boolean;
};

export const TraceDataToolbar: React.FC<TraceDataToolbarProps> = ({
  dataToView,
  setActiveSection,
  isLoading = false,
}) => {
  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  useHotkeys(
    "a",
    (e) => {
      e.preventDefault();
      setActiveSection(DetailsActionSection.Annotate);
    },
    { enableOnFormTags: false, enabled: canAnnotateTraceSpanThread },
    [setActiveSection, canAnnotateTraceSpanThread],
  );

  const rows = useMemo(() => (dataToView ? [dataToView] : []), [dataToView]);

  const isSpan = dataToView ? isObjectSpan(dataToView) : false;
  const dataType = isSpan ? "spans" : "traces";
  const inspectType: BASE_TRACE_DATA_TYPE = isSpan
    ? (dataToView as Span).type
    : TRACE_TYPE_FOR_TREE;

  return (
    <div className="flex h-10 shrink-0 items-center gap-2 border-b bg-muted/50 px-4">
      <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
        Inspect:
      </span>
      {isLoading || !dataToView ? (
        <Skeleton className="h-4 w-32" />
      ) : (
        <>
          <BaseTraceDataTypeIcon type={inspectType} />
          <span className="comet-body-xs-accented truncate">
            {dataToView?.name}
          </span>
        </>
      )}

      <div className="flex-auto" />

      <AddToDropdown
        getDataForExport={async () => rows}
        selectedRows={rows}
        dataType={dataType}
        buttonVariant="ghost"
        buttonSize="2xs"
        disabled={isLoading || !dataToView}
      />
      {canAnnotateTraceSpanThread && (
        <DetailsActionSectionToggle
          activeSection={null}
          setActiveSection={setActiveSection}
          layoutSize={ButtonLayoutSize.Large}
          type={DetailsActionSection.Annotate}
          variant="ghost"
          buttonSize="2xs"
          hotkey="A"
          disabled={isLoading || !dataToView}
        />
      )}
    </div>
  );
};
