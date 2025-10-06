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
import ProviderPrettyView from "./ProviderPrettyView";
import { cn } from "@/lib/utils";
import isObject from "lodash/isObject";
import isUndefined from "lodash/isUndefined";

interface PrettyViewContainerProps {
  data: Trace | Span;
  type: "input" | "output";
  className?: string;
}

const PrettyViewContainer: React.FC<PrettyViewContainerProps> = ({
  data,
  type,
  className,
}) => {
  const jsonViewTheme = useJsonViewTheme();

  const { provider, formattedData, shouldUsePrettyView, isValidData } =
    useMemo(() => {
      // Validate that data is actually a Trace or Span
      if (!isTraceOrSpan(data)) {
        return {
          provider: null,
          formattedData: null,
          shouldUsePrettyView: false,
          isValidData: false,
        };
      }

      const provider = detectProvider(data);

      if (!provider || !supportsPrettyView(provider)) {
        return {
          provider: null,
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

      return {
        provider,
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
            collapseStringsAfterLength={10000}
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

  return (
    <ProviderPrettyView
      provider={provider!}
      data={formattedData}
      type={type}
      className={className}
    />
  );
};

export default PrettyViewContainer;
