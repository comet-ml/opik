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
    <div className="m-auto flex h-full min-h-[400px] max-w-[1040px] flex-col overflow-hidden">
      {header && <div className="py-10">{header}</div>}
      <div className="flex-auto overflow-y-auto">{children}</div>
      {footer && (
        <div className="border-border flex justify-end gap-2 border-t pb-6 pt-4">
          {footer}
        </div>
      )}
    </div>
  );
};

export default SMEFlowLayout;
