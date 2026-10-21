import React from "react";
import JsonView from "react18-json-view";
import isObject from "lodash/isObject";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

const AnnotatorFieldValue: React.FC<{ value: unknown }> = ({ value }) => {
  const jsonViewTheme = useJsonViewTheme();

  if (value === undefined || value === null) {
    return (
      <span className="comet-body-s text-light-slate">
        Not present on this item
      </span>
    );
  }

  return isObject(value) ? (
    <JsonView
      src={value}
      {...jsonViewTheme}
      className="comet-code"
      collapseStringsAfterLength={10000}
      enableClipboard={false}
    />
  ) : (
    <MarkdownPreview>{String(value)}</MarkdownPreview>
  );
};

export default AnnotatorFieldValue;
