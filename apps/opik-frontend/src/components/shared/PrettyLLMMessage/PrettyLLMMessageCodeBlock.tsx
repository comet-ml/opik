import React, { useMemo } from "react";
import { highlightCode, classHighlighter } from "@lezer/highlight";
import { jsonLanguage } from "@codemirror/lang-json";
import { cn } from "@/lib/utils";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import { PrettyLLMMessageCodeBlockProps } from "./types";

const PrettyLLMMessageCodeBlock: React.FC<PrettyLLMMessageCodeBlockProps> =
  React.memo(({ code, label = "JSON", className }) => {
    const lines = useMemo(() => {
      const tree = jsonLanguage.parser.parse(code);
      const result: React.ReactNode[][] = [[]];
      let key = 0;
      highlightCode(
        code,
        tree,
        classHighlighter,
        (text, classes) => {
          const current = result[result.length - 1];
          current.push(
            classes ? (
              <span key={key++} className={classes}>
                {text}
              </span>
            ) : (
              text
            ),
          );
        },
        () => {
          result.push([]);
        },
      );
      // Drop trailing empty line produced by a final newline in source
      if (result.length > 1 && result[result.length - 1].length === 0) {
        result.pop();
      }
      return result;
    }, [code]);

    const gutterWidth = String(lines.length).length;

    return (
      <div
        className={cn(
          "overflow-hidden rounded-md border border-border bg-primary-foreground",
          className,
        )}
      >
        <div className="flex items-center justify-between border-b border-border px-3 py-0.5">
          <div className="text-xs text-muted-foreground">{label}</div>
          <CopyButton
            text={code}
            message="Code copied to clipboard"
            tooltipText="Copy code"
            size="icon-2xs"
            className="p-0"
          />
        </div>
        <div className="max-h-[500px] overflow-auto">
          <pre className="static-code-highlight m-0 p-0">
            {lines.map((tokens, i) => (
              <div key={i} className="flex">
                <span
                  className="static-code-gutter shrink-0 select-none px-2 text-right"
                  style={{ minWidth: `${gutterWidth + 2}ch` }}
                >
                  {i + 1}
                </span>
                <span className="flex-1 px-1">{tokens}</span>
              </div>
            ))}
          </pre>
        </div>
      </div>
    );
  });

PrettyLLMMessageCodeBlock.displayName = "PrettyLLMMessageCodeBlock";

export default PrettyLLMMessageCodeBlock;
