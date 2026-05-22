import React from "react";

type SMEFlowLayoutProps = {
  header?: React.ReactNode;
  footer?: React.ReactNode;
  children: React.ReactNode;
};

const SMEFlowLayout: React.FC<SMEFlowLayoutProps> = ({
  header,
  children,
  footer,
}) => {
  return (
    <div className="flex h-full flex-col overflow-hidden px-10 pb-4 pt-8">
      {header && <div className="pb-4">{header}</div>}
      <div className="flex-auto overflow-y-auto overflow-x-hidden pb-4">
        {children}
      </div>
      {footer && (
        <div className="flex justify-between gap-2 border-t border-border pt-3">
          {footer}
        </div>
      )}
    </div>
  );
};

export default SMEFlowLayout;
