import React, { useMemo } from "react";
import { Trace, Span } from "@/types/traces";
import { detectProvider, supportsPrettyView } from "@/lib/provider-detection";
import {
  formatProviderData,
  canFormatProviderData,
} from "@/lib/provider-schemas";
import { isTraceOrSpan } from "@/lib/type-guards";
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

  const { formattedData, shouldUsePrettyView, isValidData } = useMemo(() => {
    // Validate that data is actually a Trace or Span
    if (!isTraceOrSpan(data)) {
      console.log("🔍 CompactPrettyView: Invalid data structure", data);
      return {
        formattedData: null,
        shouldUsePrettyView: false,
        isValidData: false,
      };
    }

    const provider = detectProvider(data);
    console.log("🔍 CompactPrettyView: Detected provider", provider, "for", type);

    if (!provider || !supportsPrettyView(provider)) {
      console.log("🔍 CompactPrettyView: Provider not supported", provider);
      return {
        formattedData: null,
        shouldUsePrettyView: false,
        isValidData: true,
      };
    }

    const rawData = type === "input" ? data.input : data.output;
    const formattedData = formatProviderData(provider, rawData, type);

    const shouldUsePrettyView =
      formattedData !== null &&
      formattedData.content.length > 0 &&
      canFormatProviderData(provider, rawData, type);

    console.log("🔍 CompactPrettyView: Pretty view result", {
      shouldUsePrettyView,
      contentLength: formattedData?.content?.length || 0,
      provider,
      type
    });

    return {
      formattedData,
      shouldUsePrettyView,
      isValidData: true,
    };
  }, [data, type]);

  // Handle invalid data
  if (!isValidData) {
    return (
      <span className={cn("text-muted-foreground", className)}>
        Invalid data structure
      </span>
    );
  }

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
      {/* DEBUG: Visual indicator for pretty view */}
      <div className="mb-1 text-xs text-green-600 font-semibold">
        ✨ PRETTY VIEW ACTIVE ✨
      </div>
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
