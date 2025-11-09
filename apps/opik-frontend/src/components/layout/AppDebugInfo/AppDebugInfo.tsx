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
  const cometDebuggerModeEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.COMET_DEBUGGER_MODE_ENABLED,
  );
  const setShowAppDebugInfo = useDebugStore(
    (state) => state.setShowAppDebugInfo,
  );

  useEffect(() => {
    const localStorageValue = localStorage.getItem(COMET_DEBUGGER_MODE_KEY);
    const shouldShowAppDebugInfo =
      cometDebuggerModeEnabled && localStorageValue?.toLowerCase() === "true";

    setShowAppDebugInfo(shouldShowAppDebugInfo);
  }, [cometDebuggerModeEnabled, setShowAppDebugInfo]);

  // Keyboard shortcut handler for debugger mode: Meta/Ctrl + c + .
  useEffect(() => {
    if (!cometDebuggerModeEnabled) {
      return;
    }

    let isWaitingForPeriod = false;

    const handleKeyDown = (event: KeyboardEvent) => {
      const isMetaOrCtrl = event.metaKey || event.ctrlKey;
      const isCKey = event.key === "c" || event.key === "C";
      const isPeriodKey = event.key === "." || event.key === ">";

      // First part: Meta/Ctrl + c - start waiting for period
      if (isMetaOrCtrl && isCKey && !isWaitingForPeriod) {
        isWaitingForPeriod = true;
        return;
      }

      // Second part: Period while still holding Meta/Ctrl - complete sequence
      if (isMetaOrCtrl && isPeriodKey && isWaitingForPeriod) {
        event.preventDefault();
        event.stopPropagation();
        isWaitingForPeriod = false;

        // Toggle debugger mode
        const shouldShowAppDebugInfo = !showAppDebugInfo;
        localStorage.setItem(
          COMET_DEBUGGER_MODE_KEY,
          String(shouldShowAppDebugInfo).toLowerCase(),
        );
        setShowAppDebugInfo(shouldShowAppDebugInfo);
        return;
      }

      // If waiting for period and any other key is pressed, reset sequence
      if (isWaitingForPeriod) {
        isWaitingForPeriod = false;
      }
    };

    window.addEventListener("keydown", handleKeyDown);

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [cometDebuggerModeEnabled, setShowAppDebugInfo, showAppDebugInfo]);

  return (
    showAppDebugInfo && (
      <>
        <div className="flex items-center">
          <AppNetworkStatus />
        </div>

        {APP_VERSION && (
          <div
            className="flex items-center gap-2"
            onClick={() => {
              copy(APP_VERSION);
              toast({ description: "Successfully copied version" });
            }}
          >
            <span className="comet-body-s-accented flex items-center gap-2 truncate">
              <OpikIcon className="size-5" />
              OPIK VERSION {APP_VERSION}
            </span>
            <Copy className="size-4 shrink-0" />
          </div>
        )}
      </>
    )
  );
};

export default AppDebugInfo;
