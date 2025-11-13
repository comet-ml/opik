import React, { useCallback, useMemo } from "react";
import { Eye, EyeOff, ScanText } from "lucide-react";

import { DropdownOption, OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  TREE_DATABLOCK_TYPE,
  TreeNodeConfig,
} from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const OPTIONS: DropdownOption<TREE_DATABLOCK_TYPE>[] = [
  { label: "Duration", value: TREE_DATABLOCK_TYPE.DURATION },
  { label: "Number of tokens", value: TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS },
  { label: "Tokens breakdown", value: TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN },
  { label: "Estimated cost", value: TREE_DATABLOCK_TYPE.ESTIMATED_COST },
  { label: "Number of scores", value: TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES },
  {
    label: "Number of comments",
    value: TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS,
  },
  { label: "Number of tags", value: TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS },
  { label: "Model", value: TREE_DATABLOCK_TYPE.MODEL },
];

type SpanDetailsButtonProps = {
  config: TreeNodeConfig;
  onConfigChange: OnChangeFn<TreeNodeConfig>;
};

const SpanDetailsButton: React.FC<SpanDetailsButtonProps> = ({
  config,
  onConfigChange,
}) => {
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const options = useMemo(() => {
    return isGuardrailsEnabled
      ? [
          { label: "Guardrails", value: TREE_DATABLOCK_TYPE.GUARDRAILS },
          ...OPTIONS,
        ]
      : OPTIONS;
  }, [isGuardrailsEnabled]);

  const toggleColumns = useCallback(
    (value: boolean) => {
      const newConfig: Partial<TreeNodeConfig> = {
        [TREE_DATABLOCK_TYPE.DURATION_TIMELINE]: value,
      };
      options.forEach(({ value: key }) => {
        newConfig[key] = value;
      });
      onConfigChange(newConfig as TreeNodeConfig);
    },
    [onConfigChange, options],
  );

  return (
    <DropdownMenu>
      <TooltipWrapper content="Span details">
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-2xs">
            <ScanText />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent className="relative max-w-72 p-0" align="end">
        <div className="max-h-[calc(var(--radix-popper-available-height)-60px)] overflow-y-auto overflow-x-hidden pb-1">
          {options.map(({ label, value }) => (
            <DropdownMenuCustomCheckboxItem
              key={value}
              checked={config[value]}
              onSelect={(event) => event.preventDefault()}
              onCheckedChange={() =>
                onConfigChange((config) => ({
                  ...config,
                  [value]: !config[value],
                }))
              }
            >
              {label}
            </DropdownMenuCustomCheckboxItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuCustomCheckboxItem
            checked={config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE]}
            onSelect={(event) => event.preventDefault()}
            onCheckedChange={() =>
              onConfigChange((config) => ({
                ...config,
                [TREE_DATABLOCK_TYPE.DURATION_TIMELINE]:
                  !config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE],
              }))
            }
          >
            Duration timeline
          </DropdownMenuCustomCheckboxItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => toggleColumns(true)}>
            <Eye className="mr-2 size-4" />
            Show all
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => toggleColumns(false)}>
            <EyeOff className="mr-2 size-4" />
            Hide all
          </DropdownMenuItem>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SpanDetailsButton;
