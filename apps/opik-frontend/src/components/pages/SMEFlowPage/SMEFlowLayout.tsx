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
    <div className="m-auto flex h-full min-h-[400px] w-[90vw] min-w-[1040px] flex-col overflow-x-auto overflow-y-hidden">
      {header && <div className="py-10">{header}</div>}
      <div className="flex-auto overflow-y-auto overflow-x-hidden pb-4">
        {children}
      </div>
      {footer && (
        <div className="flex justify-between gap-2 border-t border-border pb-6 pt-4">
          {footer}
        </div>
      )}
    </div>
  );
};

export default SMEFlowLayout;
