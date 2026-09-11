import { ReactNode } from "react";
import { cn } from "@/lib/utils";

interface SyntaxHighlighterLayoutProps {
  children: ReactNode;
  leftHeader: ReactNode;
  rightHeader: ReactNode;
  transparent?: boolean;
  fullHeight?: boolean;
}

const SyntaxHighlighterLayout: React.FC<SyntaxHighlighterLayoutProps> = ({
  children,
  leftHeader,
  rightHeader,
  transparent,
  fullHeight,
}) => {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-md",
        transparent ? "" : "bg-primary-foreground",
        fullHeight && "flex flex-1 flex-col",
      )}
    >
      <div className="flex h-10 items-center justify-between border-b border-border pr-2">
        <div className="flex min-w-40">{leftHeader}</div>
        <div className="flex flex-1 items-center justify-end gap-0.5">
          {rightHeader}
        </div>
      </div>
      <div className={cn(fullHeight && "min-h-0 flex-1 overflow-auto")}>
        {children}
      </div>
    </div>
  );
};

export default SyntaxHighlighterLayout;
