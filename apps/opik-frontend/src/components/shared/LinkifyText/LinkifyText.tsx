import React from "react";
import isString from "lodash/isString";
import Linkify from "linkify-react";
import type { Opts, IntermediateRepresentation } from "linkifyjs";

const URL_PATTERN = /https?:\/\//;

const LINKIFY_OPTIONS: Opts = {
  defaultProtocol: "https",
  target: "_blank",
  rel: "noopener noreferrer",
  className: "break-all text-blue-600 underline hover:text-blue-800",
  ignoreTags: ["script", "style"],
  validate: {
    url: (value: string) => URL_PATTERN.test(value),
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

const LinkifyText: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  if (isString(children) && !URL_PATTERN.test(children)) {
    return children;
  }
  return <Linkify options={LINKIFY_OPTIONS}>{children}</Linkify>;
};

export default LinkifyText;
