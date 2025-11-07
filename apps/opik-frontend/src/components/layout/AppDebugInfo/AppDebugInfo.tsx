import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { toast } from "@/components/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import AppNetworkStatus from "@/plugins/comet/AppNetworkStatus";
import OpikIcon from "@/icons/opik.svg?react";
import { COMET_DEBUGGER_MODE_KEY } from "@/plugins/comet/UserMenuAppDebugInfoToggle";
import { useDebugStore } from "@/store/DebugStore";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";
import { useEffect } from "react";

const AppDebugInfo = () => {
  const showAppDebugInfo = useDebugStore((state) => state.showAppDebugInfo);
  const isCometDebuggerModeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.COMET_DEBUGGER_MODE_ENABLED,
  );
  const setShowAppDebugInfo = useDebugStore(
    (state) => state.setShowAppDebugInfo,
  );

  useEffect(() => {
    const localStorageValue = localStorage.getItem(COMET_DEBUGGER_MODE_KEY);
    const shouldShowAppDebugInfo =
      isCometDebuggerModeEnabled && localStorageValue?.toLowerCase() === "true";

    setShowAppDebugInfo(shouldShowAppDebugInfo);
  }, [isCometDebuggerModeEnabled, setShowAppDebugInfo]);

  return (
    showAppDebugInfo && (
      <>
        <div className="flex items-center">
          <AppNetworkStatus />
        </div>
        <div
          className="flex items-center gap-2"
          onClick={() => {
            copy(APP_VERSION);
            toast({ description: "Successfully copied version" });
          }}
        >
          <span className="comet-body-s-accented truncate flex items-center gap-2">
            <OpikIcon className="size-5" />
            OPIK VERSION {APP_VERSION}
          </span>
          <Copy className="size-4 shrink-0" />
        </div>
      </>
    )
  );
};

export default AppDebugInfo;
