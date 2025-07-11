import React, { useCallback, useMemo, useState } from "react";
import copy from "clipboard-copy";
import {
  Copy,
  MessagesSquare,
  MoreHorizontal,
  Share,
  Trash,
} from "lucide-react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  DropdownOption,
  OnChangeFn,
} from "@/types/shared";
import { Filters } from "@/types/filters";
import { Span, SPAN_TYPE, Trace } from "@/types/traces";
import useTraceDeleteMutation from "@/api/traces/useTraceDeleteMutation";
import { useToast } from "@/components/ui/use-toast";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SelectItem } from "@/components/ui/select";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import SelectBox, {
  SelectBoxProps,
} from "@/components/shared/SelectBox/SelectBox";
import ExpandableSearchInput from "@/components/shared/ExpandableSearchInput/ExpandableSearchInput";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { TREE_FILTER_COLUMNS } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/helpers";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { GuardrailResult } from "@/types/guardrails";
import { getJSONPaths } from "@/lib/utils";

const PANEL_ELEMENTS_EXPANDED_SIZE = [
  { name: "SEPARATOR", size: 25 },
  { name: "GO_TO_THREAD", size: 110 },
  { name: "PADDING", size: 24 },
  { name: "FILTER", size: 60 },
  { name: "SEPARATOR", size: 25 },
  { name: "MORE", size: 32 },
];

const MIN_PANEL_WIDTH = PANEL_ELEMENTS_EXPANDED_SIZE.reduce(
  (acc, e) => acc + e.size,
  0,
);

const SEARCH_SPACE_RESERVATION = 200;

type TraceDetailsActionsPanelProps = {
  projectId: string;
  traceId: string;
  spanId: string;
  threadId?: string;
  setThreadId?: OnChangeFn<string | null | undefined>;
  onClose: () => void;
  isSpansLazyLoading: boolean;
  search?: string;
  setSearch: OnChangeFn<string | undefined>;
  filters: Filters;
  setFilters: OnChangeFn<Filters>;
  treeData: Array<Trace | Span>;
};

const TraceDetailsActionsPanel: React.FunctionComponent<
  TraceDetailsActionsPanelProps
> = ({
  projectId,
  traceId,
  spanId,
  threadId,
  setThreadId,
  onClose,
  isSpansLazyLoading,
  search,
  setSearch,
  filters,
  setFilters,
  treeData,
}) => {
  const [popupOpen, setPopupOpen] = useState<boolean>(false);
  const [isSmall, setIsSmall] = useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const { toast } = useToast();

  const { mutate } = useTraceDeleteMutation();

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    setIsSmall(node.clientWidth < MIN_PANEL_WIDTH + SEARCH_SPACE_RESERVATION);
  });

  const handleTraceDelete = useCallback(() => {
    onClose();
    mutate({
      traceId,
      projectId,
    });
  }, [onClose, mutate, traceId, projectId]);

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
        type: {
          keyComponentProps: {
            options: [
              {
                value: SPAN_TYPE.general,
                label: "General",
              },
              {
                value: SPAN_TYPE.tool,
                label: "Tool",
              },
              {
                value: SPAN_TYPE.llm,
                label: "LLM call",
              },
              ...(isGuardrailsEnabled
                ? [
                    {
                      value: SPAN_TYPE.guardrail,
                      label: "Guardrail",
                    },
                  ]
                : []),
            ],
            placeholder: "Select type",
            renderOption: (option: DropdownOption<SPAN_TYPE>) => {
              return (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  withoutCheck
                  wrapperAsChild={true}
                >
                  <div className="flex w-full items-center gap-1.5">
                    <BaseTraceDataTypeIcon type={option.value} />
                    {option.label}
                  </div>
                </SelectItem>
              );
            },
          },
        },
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
    <div ref={ref} className="flex flex-auto items-center justify-between">
      {setThreadId && threadId ? (
        <div className="flex items-center">
          <Separator orientation="vertical" className="mx-3 h-4" />
          <TooltipWrapper content="Go to thread">
            <Button
              variant="outline"
              size={isSmall ? "icon-sm" : "sm"}
              onClick={() => setThreadId(threadId)}
            >
              {isSmall ? <MessagesSquare /> : "Go to thread"}
            </Button>
          </TooltipWrapper>
        </div>
      ) : (
        <div />
      )}
      <div className="flex items-center gap-2 pl-6">
        <div className="flex min-w-44 max-w-56 flex-auto justify-end overflow-hidden">
          <ExpandableSearchInput
            value={search}
            placeholder="Search by all fields"
            onChange={setSearch}
            disabled={isSpansLazyLoading}
          />
        </div>
        <FiltersButton
          columns={filtersColumnData}
          filters={filters}
          onChange={setFilters}
          config={filtersConfig as never}
          layout="icon"
          variant="outline"
          disabled={isSpansLazyLoading}
          align="end"
        />
        <Separator orientation="vertical" className="mx-1 h-4" />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon-sm">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem
              onClick={() => {
                toast({
                  description: "URL copied to clipboard",
                });
                copy(window.location.href);
              }}
            >
              <Share className="mr-2 size-4" />
              Share
            </DropdownMenuItem>
            <TooltipWrapper content={traceId} side="left">
              <DropdownMenuItem
                onClick={() => {
                  toast({
                    description: `Trace ID copied to clipboard`,
                  });
                  copy(traceId);
                }}
              >
                <Copy className="mr-2 size-4" />
                Copy trace ID
              </DropdownMenuItem>
            </TooltipWrapper>
            {spanId && (
              <TooltipWrapper content={spanId} side="left">
                <DropdownMenuItem
                  onClick={() => {
                    toast({
                      description: `Span ID copied to clipboard`,
                    });
                    copy(spanId);
                  }}
                >
                  <Copy className="mr-2 size-4" />
                  Copy span ID
                </DropdownMenuItem>
              </TooltipWrapper>
            )}
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setPopupOpen(true)}>
              <Trash className="mr-2 size-4" />
              Delete trace
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <ConfirmDialog
          open={popupOpen}
          setOpen={setPopupOpen}
          onConfirm={handleTraceDelete}
          title="Delete trace"
          description="Deleting a trace will also remove the trace data from related experiment samples. This action can’t be undone. Are you sure you want to continue?"
          confirmText="Delete trace"
          confirmButtonVariant="destructive"
        />
      </div>
    </div>
  );
};

export default TraceDetailsActionsPanel;
