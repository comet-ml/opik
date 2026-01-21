import React from "react";
import { Check, X } from "lucide-react";
import { WidgetProps } from "@/types/custom-view";
import { Tag } from "@/components/ui/tag";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";

const SimpleWidget: React.FC<WidgetProps> = ({ value, label }) => {
  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  // Detect type and render accordingly
  const valueType = typeof value;

  // Boolean rendering
  if (valueType === "boolean") {
    const boolValue = Boolean(value);
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <Tag
          variant={boolValue ? "green" : "red"}
          size="lg"
          className="inline-flex items-center gap-1"
        >
          {boolValue ? (
            <>
              <Check className="size-3" />
              True
            </>
          ) : (
            <>
              <X className="size-3" />
              False
            </>
          )}
        </Tag>
      </div>
    );
  }

  // Number rendering
  if (valueType === "number") {
    const numValue = Number(value);
    const isValid = !isNaN(numValue);
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="comet-title-l">
          {isValid ? numValue.toLocaleString() : String(value)}
        </div>
      </div>
    );
  }

  // String rendering with MarkdownPreview
  if (valueType === "string") {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm">
          <MarkdownPreview>{value as string}</MarkdownPreview>
        </div>
      </div>
    );
  }

  // Fallback for other types (objects, arrays, etc.)
  const displayValue = JSON.stringify(value);
  return (
    <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
      <div className="comet-body-s-accented mb-2">{label}</div>
      <div className="whitespace-pre-wrap break-words text-sm">
        {displayValue}
      </div>
    </div>
  );
};

export default SimpleWidget;
