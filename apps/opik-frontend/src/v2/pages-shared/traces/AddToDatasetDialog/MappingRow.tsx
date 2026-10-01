import React from "react";
import { MessageCircleWarning, MoveRight, X } from "lucide-react";

import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { JsonObject } from "@/types/shared";
import { cn } from "@/lib/utils";
import PathSourcePicker from "./PathSourcePicker";
import {
  MAX_FIELD_NAME_LENGTH,
  MappingRow as MappingRowType,
} from "./fieldMappingTypes";
import { FieldRowError } from "./useFieldMappings";
import { FieldCoverage } from "./useFieldCoverage";

const ROW_ERROR_TEXT: Record<FieldRowError, string> = {
  blank: "Field name is required",
  duplicate: "Field name is already used",
  too_long: `Field name must be ${MAX_FIELD_NAME_LENGTH} characters or fewer`,
  no_path: "Select a field to map",
};

type MappingRowProps = {
  row: MappingRowType;
  treeData: JsonObject;
  coverage?: FieldCoverage;
  rowError?: FieldRowError;
  newColumnItemCount?: number;
  isPending?: boolean;
  entityLabel?: string;
  autoFocusName?: boolean;
  autoOpenSource?: boolean;
  onRename?: (name: string) => void;
  onPathSelect?: (path: string) => void;
  onRemove?: () => void;
};

const MappingRow: React.FunctionComponent<MappingRowProps> = ({
  row,
  treeData,
  coverage,
  rowError,
  newColumnItemCount,
  isPending,
  entityLabel,
  autoFocusName,
  autoOpenSource,
  onRename,
  onPathSelect,
  onRemove,
}) => {
  const isManaged = row.kind === "managed";
  const isCustom = row.kind === "custom";

  const renderCoverage = () => {
    if (!coverage || coverage.covered >= coverage.total) return null;

    return (
      <TooltipWrapper
        content={`${coverage.covered} of ${coverage.total} selected ${
          coverage.total === 1 ? "item has" : "items have"
        } a value for this field`}
      >
        <Tag variant="orange" size="sm" className="shrink-0 text-xs">
          {coverage.covered}/{coverage.total}
        </Tag>
      </TooltipWrapper>
    );
  };

  const renderName = () => {
    if (!isCustom) {
      return (
        <div className="flex h-6 items-center rounded-md border border-border bg-background px-2">
          <span className="comet-body-xs truncate">{row.name}</span>
        </div>
      );
    }

    return (
      <Input
        dimension="xs"
        value={row.name}
        placeholder="Field name"
        className={cn(
          "px-2",
          rowError && rowError !== "no_path" && "border-destructive",
        )}
        autoFocus={autoFocusName}
        onFocus={(event) => autoFocusName && event.currentTarget.select()}
        onChange={(event) => onRename?.(event.target.value)}
      />
    );
  };

  const renderSource = () => {
    if (isManaged) {
      return (
        <div className="flex h-6 items-center gap-1 rounded-md border border-border bg-muted px-2">
          <span className="comet-body-xs min-w-0 flex-1 truncate text-muted-slate">
            Managed by Opik
          </span>
          {renderCoverage()}
        </div>
      );
    }

    return (
      <PathSourcePicker
        fieldName={row.name}
        path={row.path}
        badge={renderCoverage()}
        invalid={rowError === "no_path"}
        defaultOpen={autoOpenSource}
        treeData={treeData}
        isPending={isPending}
        entityLabel={entityLabel}
        onSelect={(path) => onPathSelect?.(path)}
      />
    );
  };

  return (
    <div
      data-testid={`mapping-row-${row.id}`}
      className="rounded-md border border-border px-2 py-[5px]"
    >
      <div className="flex h-6 items-center gap-2">
        <div className="w-[calc((100%-40px)/2)] min-w-0 shrink-0">
          {renderName()}
        </div>
        <Button variant="ghost" size="icon-2xs" disabled className="shrink-0">
          <MoveRight />
        </Button>
        <div className="min-w-0 flex-1">{renderSource()}</div>
        {onRemove && (
          <Button
            variant="minimal"
            size="icon-2xs"
            className="shrink-0"
            onClick={onRemove}
          >
            <X />
          </Button>
        )}
      </div>
      {rowError && (
        <p className="comet-body-xs mt-1 text-destructive">
          {ROW_ERROR_TEXT[rowError]}
        </p>
      )}
      {!rowError && Boolean(newColumnItemCount) && (
        <div className="flex items-center gap-1.5 py-2">
          <MessageCircleWarning className="size-3 shrink-0 text-muted-slate" />
          <span className="comet-body-xs text-muted-slate">
            New field — the {newColumnItemCount} items already in this dataset
            will display this field as empty.
          </span>
        </div>
      )}
    </div>
  );
};

export default MappingRow;
