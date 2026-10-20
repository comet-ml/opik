import React from "react";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { Columns2, GripVertical, MoveRight, X } from "lucide-react";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { JsonObject } from "@/types/shared";
import AnnotatorPathInput from "./AnnotatorPathInput";
import { MAX_FIELD_LABEL_LENGTH } from "./annotatorViewConfig";
import {
  AnnotatorFieldDraft,
  isFieldLabelInvalid,
} from "./useAnnotatorViewDraft";

type FieldRowProps = {
  field: AnnotatorFieldDraft;
  treeData: JsonObject;
  isPending: boolean;
  onChange: (field: AnnotatorFieldDraft) => void;
  onRemove: () => void;
};

const FieldRow: React.FC<FieldRowProps> = ({
  field,
  treeData,
  isPending,
  onChange,
  onRemove,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition } =
    useSortable({ id: field.id });
  const isLabelInvalid = isFieldLabelInvalid(field);

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className="rounded-md border border-border bg-background px-2 py-[5px]"
      data-testid="annotator-field-row"
    >
      <div className="flex h-6 items-center gap-2">
        <GripVertical
          className="size-3.5 shrink-0 cursor-move text-light-slate"
          {...attributes}
          {...listeners}
        />
        <div className="w-[calc((100%-120px)/2)] min-w-0 shrink-0">
          <Input
            dimension="xs"
            value={field.label}
            placeholder="Label"
            className={cn("px-2", isLabelInvalid && "border-destructive")}
            onChange={(event) =>
              onChange({ ...field, label: event.target.value })
            }
          />
        </div>
        <Button variant="ghost" size="icon-2xs" disabled className="shrink-0">
          <MoveRight />
        </Button>
        <div className="min-w-0 flex-1">
          <AnnotatorPathInput
            path={field.path}
            treeData={treeData}
            isPending={isPending}
            onChange={(path) => onChange({ ...field, path })}
          />
        </div>
        <TooltipWrapper
          content={field.half_width ? "Full width" : "Half width"}
        >
          <Button
            variant="minimal"
            size="icon-2xs"
            aria-pressed={Boolean(field.half_width)}
            className={cn(
              "shrink-0",
              field.half_width && "bg-primary-100 text-primary",
            )}
            onClick={() =>
              onChange({ ...field, half_width: !field.half_width })
            }
          >
            <Columns2 />
          </Button>
        </TooltipWrapper>
        <Button
          variant="minimal"
          size="icon-2xs"
          aria-label={`Remove ${field.label}`}
          className="shrink-0"
          onClick={onRemove}
        >
          <X />
        </Button>
      </div>
      {isLabelInvalid && (
        <p className="comet-body-xs mt-1 text-destructive">
          {field.label.trim()
            ? `Label must be ${MAX_FIELD_LABEL_LENGTH} characters or fewer`
            : "Label is required"}
        </p>
      )}
    </div>
  );
};

export default FieldRow;
