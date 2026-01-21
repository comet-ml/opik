import React from "react";
import { WidgetProps } from "@/types/custom-view";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";

const CodeWidget: React.FC<WidgetProps> = ({ value, label }) => {
  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  // Convert value to object for SyntaxHighlighter
  let dataObject: object;

  if (typeof value === "string") {
    // Try to parse as JSON
    try {
      dataObject = JSON.parse(value);
    } catch {
      // If not JSON, wrap in object
      dataObject = { code: value };
    }
  } else if (typeof value === "object") {
    // Already an object
    dataObject = value as object;
  } else {
    // For primitives, wrap in object
    dataObject = { value };
  }

  return (
    <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
      <div className="comet-body-s-accented mb-3">{label}</div>
      <SyntaxHighlighter
        data={dataObject}
        prettifyConfig={{ fieldType: "output" }}
        withSearch
      />
    </div>
  );
};

export default CodeWidget;
