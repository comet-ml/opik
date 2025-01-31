import React from "react";

type IntegrationListLayoutProps = {
  leftSidebar: React.ReactNode;
  rightSidebar: React.ReactNode;
  children: React.ReactNode;
};
const IntegrationListLayout: React.FC<IntegrationListLayoutProps> = ({
  leftSidebar,
  rightSidebar,
  children,
}) => {
  return (
    <div className="m-auto flex w-full max-w-[1250px] gap-6">
      <div className="sticky top-0 flex w-[250px] shrink-0 flex-col gap-4 self-start">
        {leftSidebar}
      </div>
      <div className="flex w-full min-w-[450px] flex-1 grow flex-col gap-6">
        {children}
      </div>
      <div className="sticky top-0 flex w-[250px] shrink-0 flex-col gap-6 self-start">
        {rightSidebar}
      </div>
    </div>
  );
};

export default IntegrationListLayout;
