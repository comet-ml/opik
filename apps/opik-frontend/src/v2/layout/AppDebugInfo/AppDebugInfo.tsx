import { useState } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { toast } from "@/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import AppNetworkStatus from "@/v2/layout/AppNetworkStatus/AppNetworkStatus";
import OpikIcon from "@/icons/opik.svg?react";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";
import { modifierKey } from "@/lib/utils";
import usePluginsStore from "@/store/PluginsStore";

const DEBUGGER_MODE_KEY = "comet-debugger-mode"; // Same key used in EM for consistency

const AppDebugInfo = () => {
  const [showAppDebugInfo, setShowAppDebugInfo] = useState(
    () => localStorage.getItem(DEBUGGER_MODE_KEY) === "true",
  );
  const AssistantDebugInfo = usePluginsStore(
    (state) => state.AssistantDebugInfo,
  );

  // Keyboard shortcut handler for debugger mode: Meta/Ctrl + Shift + . (all pressed simultaneously)
  useHotkeys(`${modifierKey}+shift+period`, (keyboardEvent: KeyboardEvent) => {
    keyboardEvent.preventDefault();
    keyboardEvent.stopPropagation();

    // Toggle debugger mode
    setShowAppDebugInfo((prev) => {
      const newValue = !prev;
      localStorage.setItem(DEBUGGER_MODE_KEY, String(newValue));
      return newValue;
    });
  });

  return (
    showAppDebugInfo && (
      <div className="flex items-center gap-3">
        <AppNetworkStatus />

        {APP_VERSION && (
          <div
            className="flex items-center gap-1"
            onClick={() => {
              copy(APP_VERSION);
              toast({ description: "Successfully copied Opik version" });
            }}
          >
            <span className="comet-body-xs-accented flex items-center gap-1 truncate">
              <OpikIcon className="size-4" />
              OPIK VERSION {APP_VERSION}
            </span>
            <Copy className="size-3 shrink-0" />
          </div>
        )}

        {AssistantDebugInfo && <AssistantDebugInfo />}
      </div>
    )
  );
};

export default AppDebugInfo;
