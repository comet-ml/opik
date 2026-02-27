import React from "react";
import type { Opts, IntermediateRepresentation } from "linkifyjs";

export const LINKIFY_OPTIONS: Opts = {
  defaultProtocol: "https",
  target: "_blank",
  rel: "noopener noreferrer",
  className: "break-all text-blue-600 underline hover:text-blue-800",
  ignoreTags: ["script", "style"],
  validate: {
    url: (value: string) => /^https?:\/\//.test(value),
  },
  render: (ir: IntermediateRepresentation) => {
    const { class: className, ...attrs } = ir.attributes as Record<
      string,
      string
    >;
    return React.createElement(
      ir.tagName,
      {
        ...attrs,
        className,
        onClick: (e: React.MouseEvent) => e.stopPropagation(),
      },
      ir.content,
    );
  },
};
