import React from "react";
import { useIsPhone } from "@/hooks/useIsPhone";

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
  const { isPhonePortrait } = useIsPhone();

  if (isPhonePortrait) {
    return (
      <div className="m-auto flex w-full flex-col gap-6 px-4">
        <div className="flex flex-col gap-4">{leftSidebar}</div>
        <div className="flex w-full flex-col gap-6">{children}</div>
        <div className="flex flex-col gap-6">{rightSidebar}</div>
      </div>
    );
  }

  return (
    <div className="m-auto flex w-full max-w-[1440px] gap-6">
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
