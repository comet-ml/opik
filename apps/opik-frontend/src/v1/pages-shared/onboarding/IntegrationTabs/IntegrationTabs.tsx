import React from "react";

type IntegrationTabsComponents = {
  Item: React.FC<{
    isActive: boolean;
    onClick: () => void;
    children: React.ReactNode;
  }>;
  Title: React.FC<{
    children: React.ReactNode;
  }>;
};
const IntegrationTabs: React.FC<{ children: React.ReactNode }> &
  IntegrationTabsComponents = ({ children }) => {
  return <ul className="flex flex-col gap-2">{children}</ul>;
};

IntegrationTabs.Item = ({ isActive, onClick, children }) => (
  <li
    className="comet-body-s flex h-10 w-full cursor-pointer items-center gap-2 rounded-md pl-2 pr-4 text-foreground hover:bg-primary-foreground data-[status=active]:bg-primary-100"
    onClick={onClick}
    data-status={isActive ? "active" : "inactive"}
  >
    {children}
  </li>
);
IntegrationTabs.Item.displayName = "IntegrationTabsItem";

IntegrationTabs.Title = ({ children }) => (
  <h4 className="comet-title-s">{children}</h4>
);
IntegrationTabs.Title.displayName = "IntegrationTabsTitle";

export default IntegrationTabs;
