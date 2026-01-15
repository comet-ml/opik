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
    <div className="m-auto flex w-full flex-col gap-6 md:max-w-[1440px] md:flex-row">
      <div className="flex flex-col gap-4 md:sticky md:top-0 md:w-[250px] md:shrink-0 md:self-start">
        {leftSidebar}
      </div>
      <div className="flex w-full flex-col gap-6 md:min-w-[450px] md:flex-1 md:grow">
        {children}
      </div>
      <div className="flex flex-col gap-6 md:sticky md:top-0 md:w-[250px] md:shrink-0 md:self-start">
        {rightSidebar}
      </div>
    </div>
  );
};

export default IntegrationListLayout;
