import React, { useMemo, useState } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { MODE_TYPE } from "@/shared/SyntaxHighlighter/constants";
import { PrettifyConfig } from "@/shared/SyntaxHighlighter/types";
import { QuickFilterCodeConfig } from "@/shared/SyntaxHighlighter/quickFilterExtension";
import {
  QuickFilterSection,
  useQuickAttributeFilter,
} from "@/shared/filter-chips/QuickAttributeFilterContext";
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
  /** Controlled open state. Omit to let the block own it. */
  open?: boolean;
  disabled?: boolean;
  className?: string;
  quickFilterSection?: QuickFilterSection;
  /** Notified when the user toggles the section. Never called on mount. */
  onOpenChange?: (open: boolean) => void;
};

const CodeBlock: React.FC<CodeBlockProps> = ({
  title,
  data,
  prettifyConfig,
  preserveKey,
  search,
  withSearch,
  defaultOpen = true,
  open,
  disabled,
  className,
  quickFilterSection,
  onOpenChange,
}) => {
  const [uncontrolledIsOpen, setUncontrolledIsOpen] = useState(defaultOpen);
  const isOpen = open ?? uncontrolledIsOpen;
  const [localSearch, setLocalSearch] = useState("");

  const api = useQuickAttributeFilter();
  // Per-attribute "filter by this" affordance, scoped to this section. Only
  // wired when a quick-filter provider is present (i.e. the Logs page).
  const quickFilter = useMemo<QuickFilterCodeConfig | undefined>(() => {
    if (!quickFilterSection || !api) return undefined;
    return {
      canFilter: (path) => api.canFilter(quickFilterSection, path),
      onFilter: (path, value) => api.filter(quickFilterSection, path, value),
    };
  }, [api, quickFilterSection]);

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
    const nextIsOpen = !isOpen;
    // Only when this block owns the state. Writing it while controlled would
    // leave a shadow copy behind to drift from whoever actually owns it.
    if (open === undefined) setUncontrolledIsOpen(nextIsOpen);
    onOpenChange?.(nextIsOpen);
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
        <CodeBlockBody
          code={code}
          searchValue={effectiveSearch}
          quickFilter={quickFilter}
        />
      </div>
    </div>
  );
};

export default CodeBlock;
