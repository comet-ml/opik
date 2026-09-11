import React from "react";
import isString from "lodash/isString";
import Linkify from "linkify-react";
import type { Opts, IntermediateRepresentation } from "linkifyjs";
import { ArrowUpRight } from "lucide-react";

const URL_PATTERN = /https?:\/\//;

const LINKIFY_OPTIONS: Opts = {
  defaultProtocol: "https",
  target: "_blank",
  rel: "noopener noreferrer",
  className:
    "text-foreground hover:text-foreground inline-flex items-center gap-0.5 border-b border-current",
  ignoreTags: ["script", "style"],
  validate: {
    url: (value: string) => URL_PATTERN.test(value),
  },
  render: ({ attributes, content }: IntermediateRepresentation) => {
    const { class: className, ...attrs } = attributes as Record<string, string>;

    return (
      <a {...attrs} className={className} onClick={(e) => e.stopPropagation()}>
        {content}
        <ArrowUpRight className="size-3 shrink-0" />
      </a>
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
