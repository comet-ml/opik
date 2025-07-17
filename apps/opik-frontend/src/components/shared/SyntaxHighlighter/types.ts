import type { Node } from "unist";
import { MODE_TYPE } from "@/components/shared/SyntaxHighlighter/constants";

export type PrettifyConfig = {
  fieldType: "input" | "output";
};

export type CodeOutput = {
  message: string;
  mode: MODE_TYPE;
  prettified: boolean;
  canBePrettified: boolean;
};

export type VisitorNode = Node;

export type MatchIndex = {
  value: number;
};

export interface TextNode extends VisitorNode {
  type: "text";
  value: string;
}

export interface InlineCodeNode extends VisitorNode {
  type: "inlineCode";
  value: string;
}

export interface CodeNode extends VisitorNode {
  type: "code";
  value: string;
  lang?: string;
}

export interface HtmlNode extends Node {
  type: "html";
  value: string;
}
