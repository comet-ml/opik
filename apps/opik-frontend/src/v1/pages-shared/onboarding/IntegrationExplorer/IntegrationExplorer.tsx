import React from "react";
import { IntegrationExplorerProvider } from "./IntegrationExplorerContext";

import IntegrationSearch from "./IntegrationSearch";
import IntegrationQuickInstall from "./IntegrationQuickInstall";
import IntegrationTypeScriptSDK from "./IntegrationTypeScriptSDK";
import IntegrationGetHelp from "./IntegrationGetHelp";
import IntegrationCopyApiKey from "./IntegrationCopyApiKey";
import IntegrationSkip from "./IntegrationSkip";
import IntegrationTabs from "./IntegrationTabs";
import IntegrationGrid from "./IntegrationGrid";

export type IntegrationExplorerProps = {
  children: React.ReactNode;
  source?: string;
};

const IntegrationExplorer: React.FunctionComponent<IntegrationExplorerProps> & {
  Search: typeof IntegrationSearch;
  QuickInstall: typeof IntegrationQuickInstall;
  TypeScriptSDK: typeof IntegrationTypeScriptSDK;
  GetHelp: typeof IntegrationGetHelp;
  CopyApiKey: typeof IntegrationCopyApiKey;
  Skip: typeof IntegrationSkip;
  Tabs: typeof IntegrationTabs;
  Grid: typeof IntegrationGrid;
} = ({ children, source }) => {
  return (
    <IntegrationExplorerProvider source={source}>
      {children}
    </IntegrationExplorerProvider>
  );
};

IntegrationExplorer.Search = IntegrationSearch;
IntegrationExplorer.QuickInstall = IntegrationQuickInstall;
IntegrationExplorer.TypeScriptSDK = IntegrationTypeScriptSDK;
IntegrationExplorer.GetHelp = IntegrationGetHelp;
IntegrationExplorer.CopyApiKey = IntegrationCopyApiKey;
IntegrationExplorer.Skip = IntegrationSkip;
IntegrationExplorer.Tabs = IntegrationTabs;
IntegrationExplorer.Grid = IntegrationGrid;

export default IntegrationExplorer;
