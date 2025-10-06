import React, { useMemo } from "react";
import { Trace, Span } from "@/types/traces";
import { detectProvider, supportsPrettyView } from "@/lib/provider-detection";
import {
  formatProviderData,
  canFormatProviderData,
} from "@/lib/provider-schemas";
import JsonView from "react18-json-view";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";
import { cn } from "@/lib/utils";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";

interface CompactPrettyViewProps {
  data: Trace | Span;
  type: "input" | "output";
  className?: string;
  maxLength?: number;
}

const CompactPrettyView: React.FC<CompactPrettyViewProps> = ({
  data,
  type,
  className,
  maxLength = 1000,
}) => {
  const jsonViewTheme = useJsonViewTheme();

  const { formattedData, shouldUsePrettyView } = useMemo(() => {
    const provider = detectProvider(data);

    if (!provider || !supportsPrettyView(provider)) {
      return {
        formattedData: null,
        shouldUsePrettyView: false,
      };
    }

    const rawData = type === "input" ? data.input : data.output;
    const formattedData = formatProviderData(provider, rawData, type);

    const shouldUsePrettyView =
      formattedData !== null &&
      formattedData.content.length > 0 &&
      canFormatProviderData(provider, rawData, type);

    return {
      formattedData,
      shouldUsePrettyView,
    };
  }, [data, type]);

  // If we can't use pretty view, fall back to JSON view
  if (!shouldUsePrettyView || !formattedData) {
    const rawData = type === "input" ? data.input : data.output;

    if (isObject(rawData)) {
      return (
        <div className={cn("w-full", className)}>
          <JsonView
            src={rawData}
            {...jsonViewTheme}
            className="comet-code"
            collapseStringsAfterLength={maxLength}
            enableClipboard={false}
          />
        </div>
      );
    } else if (isUndefined(rawData)) {
      return <span className={cn("text-muted-foreground", className)}>-</span>;
    } else {
      return (
        <span className={cn("comet-body", className)}>{String(rawData)}</span>
      );
    }
  }

  // Truncate content if it's too long
  const displayContent =
    formattedData.content.length > maxLength
      ? formattedData.content.slice(0, maxLength) + "..."
      : formattedData.content;

  return (
    <div className={cn("w-full", className)}>
      <div className="comet-body whitespace-pre-wrap break-words">
        {displayContent}
      </div>
      {formattedData.metadata?.model && (
        <div className="comet-body-xs mt-1 text-muted-foreground">
          Model: {formattedData.metadata.model}
        </div>
      )}
    </div>
  );
};

export default CompactPrettyView;
