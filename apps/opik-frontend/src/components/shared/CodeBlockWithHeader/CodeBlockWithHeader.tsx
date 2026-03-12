import React from "react";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

type CodeBlockWithHeaderProps = {
  title: string;
  children: React.ReactNode;
  copyText?: string;
};

const CodeBlockWithHeader: React.FC<CodeBlockWithHeaderProps> = ({
  title,
  children,
  copyText,
}) => (
  <div className="overflow-hidden rounded-md border border-border bg-primary-foreground">
    <div className="flex items-center justify-between border-b border-border px-3">
      <div className="comet-body-xs text-muted-slate">{title}</div>
      {copyText && (
        <div className="-mr-2">
          <CopyButton
            message="Successfully copied code"
            text={copyText}
            tooltipText="Copy code"
          />
        </div>
      )}
    </div>
    <div className="[&>div>.absolute]:!hidden [&_.cm-editor]:!bg-primary-foreground [&_.cm-gutters]:!bg-primary-foreground">
      {children}
    </div>
  </div>
);

export default CodeBlockWithHeader;
