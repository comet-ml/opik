import { ReactNode } from "react";

interface SyntaxHighlighterLayoutProps {
  children: ReactNode;
  leftHeader: ReactNode;
  rightHeader: ReactNode;
}

const SyntaxHighlighterLayout: React.FC<SyntaxHighlighterLayoutProps> = ({
  children,
  leftHeader,
  rightHeader,
}) => {
  return (
    <div className="overflow-hidden rounded-md bg-primary-foreground">
      <div className="flex h-10 items-center justify-between border-b border-border pr-2">
        <div className="flex min-w-40">{leftHeader}</div>
        <div className="flex flex-1 items-center justify-end gap-0.5">
          {rightHeader}
        </div>
      </div>
      <div>{children}</div>
    </div>
  );
};

export default SyntaxHighlighterLayout;
