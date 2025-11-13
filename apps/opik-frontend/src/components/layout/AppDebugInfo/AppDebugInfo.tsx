import { useState } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { toast } from "@/components/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import AppNetworkStatus from "@/components/layout/AppNetworkStatus/AppNetworkStatus";
import OpikIcon from "@/icons/opik.svg?react";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";
import { modifierKey } from "@/lib/utils";

const DEBUGGER_MODE_KEY = "comet-debugger-mode"; // Same key used in EM for consistency

const AppDebugInfo = () => {
  const [showAppDebugInfo, setShowAppDebugInfo] = useState(
    () => localStorage.getItem(DEBUGGER_MODE_KEY) === "true",
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
