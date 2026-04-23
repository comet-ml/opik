import { createContext, useContext, useMemo } from "react";
import useUIConfig from "@/api/ui-config/useUIConfig";
import { UIConfig } from "@/types/ui-config";

const DEFAULT_UI_CONFIG: UIConfig = {
  default_page_size: 100,
};

const UIConfigContext = createContext<UIConfig>(DEFAULT_UI_CONFIG);

type UIConfigProviderProps = {
  children: React.ReactNode;
};

export function UIConfigProvider({ children }: UIConfigProviderProps) {
  const { data } = useUIConfig();

  const value = useMemo<UIConfig>(() => data ?? DEFAULT_UI_CONFIG, [data]);

  return (
    <UIConfigContext.Provider value={value}>
      {children}
    </UIConfigContext.Provider>
  );
}

export const useUIConfigValue = (): UIConfig => useContext(UIConfigContext);
