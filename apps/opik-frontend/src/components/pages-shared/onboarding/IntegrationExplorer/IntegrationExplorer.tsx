import React from "react";
import { IntegrationExplorerProvider } from "./IntegrationExplorerContext";

import IntegrationSearch from "./IntegrationSearch";
import IntegrationQuickInstall from "./IntegrationQuickInstall";
import IntegrationGetHelp from "./IntegrationGetHelp";
import IntegrationCopyApiKey from "./IntegrationCopyApiKey";
import IntegrationTabs from "./IntegrationTabs";
import IntegrationGrid from "./IntegrationGrid";

export type IntegrationExplorerProps = {
  children: React.ReactNode;
};

const IntegrationExplorer: React.FunctionComponent<IntegrationExplorerProps> & {
  Search: typeof IntegrationSearch;
  QuickInstall: typeof IntegrationQuickInstall;
  GetHelp: typeof IntegrationGetHelp;
  CopyApiKey: typeof IntegrationCopyApiKey;
  Tabs: typeof IntegrationTabs;
  Grid: typeof IntegrationGrid;
} = ({ children }) => {
  return <IntegrationExplorerProvider>{children}</IntegrationExplorerProvider>;
};

IntegrationExplorer.Search = IntegrationSearch;
IntegrationExplorer.QuickInstall = IntegrationQuickInstall;
IntegrationExplorer.GetHelp = IntegrationGetHelp;
IntegrationExplorer.CopyApiKey = IntegrationCopyApiKey;
IntegrationExplorer.Tabs = IntegrationTabs;
IntegrationExplorer.Grid = IntegrationGrid;

export default IntegrationExplorer;
