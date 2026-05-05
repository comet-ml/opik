import React, { useState } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { MODE_TYPE } from "@/shared/SyntaxHighlighter/constants";
import { PrettifyConfig } from "@/shared/SyntaxHighlighter/types";
import CodeBlockModeSelect from "./CodeBlockModeSelect";
import CodeBlockSearch from "./CodeBlockSearch";
import CodeBlockCopy from "./CodeBlockCopy";
import CodeBlockBody from "./CodeBlockBody";

type CodeBlockProps = {
  title: React.ReactNode;
  data: object;
  prettifyConfig?: PrettifyConfig;
  preserveKey?: string;
  search?: string;
  withSearch?: boolean;
  defaultOpen?: boolean;
  disabled?: boolean;
  className?: string;
};

const CodeBlock: React.FC<CodeBlockProps> = ({
  title,
  data,
  prettifyConfig,
  preserveKey,
  search,
  withSearch,
  defaultOpen = true,
  disabled,
  className,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  const [localSearch, setLocalSearch] = useState("");

  const { mode, setMode } = useSyntaxHighlighterMode(
    prettifyConfig,
    preserveKey,
  );
  const code = useSyntaxHighlighterCode(data, mode, prettifyConfig);
  const options = useSyntaxHighlighterOptions(
    prettifyConfig,
    code.canBePrettified,
  );

  const effectiveSearch = localSearch || search;

  const handleToggle = () => {
    if (disabled) return;
    setIsOpen((prev) => !prev);
  };

  return (
    <div
      className={cn(
        "overflow-hidden rounded-md border border-border bg-soft-background",
        isOpen && "pb-2",
        className,
      )}
    >
      <div
        className={cn(
          "flex h-8 items-center border-b px-2",
          isOpen ? "border-border" : "border-transparent",
        )}
      >
        <button
          type="button"
          aria-expanded={isOpen}
          aria-disabled={disabled ? true : undefined}
          onClick={handleToggle}
          className={cn(
            "flex h-full min-w-0 flex-1 items-center gap-1 text-left",
            disabled
              ? "cursor-not-allowed opacity-60"
              : "cursor-pointer hover:opacity-80",
          )}
        >
          <ChevronDown
            className={cn(
              "size-3.5 shrink-0 text-light-slate transition-transform duration-200",
              !isOpen && "-rotate-90",
            )}
          />
          <span className="comet-body-xs-accented truncate text-muted-slate">
            {title}
          </span>
        </button>
        <div className="relative flex shrink-0 items-center gap-2">
          <CodeBlockModeSelect
            value={code.mode}
            options={options}
            onChange={(value) => setMode(value as MODE_TYPE)}
          />
          {withSearch && (
            <CodeBlockSearch
              searchValue={localSearch}
              onSearch={setLocalSearch}
            />
          )}
          <CodeBlockCopy text={code.message} />
        </div>
      </div>
      <div className={cn("pt-2", !isOpen && "hidden")}>
        <CodeBlockBody code={code} searchValue={effectiveSearch} />
      </div>
    </div>
  );
};

export default CodeBlock;
