import React from "react";
import Logo from "@/shared/Logo/Logo";

const AgentOnboardingShell: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <div className="fixed inset-0 z-50 overflow-auto bg-soft-background">
    <div className="absolute left-[18px] top-[14.5px]">
      <Logo expanded />
    </div>
    {children}
  </div>
);

export default AgentOnboardingShell;
