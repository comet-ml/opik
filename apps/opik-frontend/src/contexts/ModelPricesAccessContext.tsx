import React, { createContext, ReactNode, useContext } from "react";

export type ModelPricesAccess = {
  canEdit: boolean;
  isPending: boolean;
  organizationName?: string;
};

// Open source has no organizations or roles, so everyone may edit; the Comet plugin narrows this to organization
// admins.
const DEFAULT_VALUE: ModelPricesAccess = {
  canEdit: true,
  isPending: false,
};

const ModelPricesAccessContext =
  createContext<ModelPricesAccess>(DEFAULT_VALUE);

export const ModelPricesAccessProvider: React.FC<{
  children: ReactNode;
  value: ModelPricesAccess;
}> = ({ children, value }) => (
  <ModelPricesAccessContext.Provider value={value}>
    {children}
  </ModelPricesAccessContext.Provider>
);

export const useModelPricesAccess = (): ModelPricesAccess =>
  useContext(ModelPricesAccessContext);
