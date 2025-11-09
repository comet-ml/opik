import { useEffect, useState } from "react";
import { toast } from "@/components/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import AppNetworkStatus from "@/plugins/comet/AppNetworkStatus";
import OpikIcon from "@/icons/opik.svg?react";
import copy from "clipboard-copy";
import { Copy } from "lucide-react";

const COMET_DEBUGGER_MODE_KEY = "comet-debugger-mode"; // Same in EM

const AppDebugInfo = () => {
  const [showAppDebugInfo, setShowAppDebugInfo] = useState(
    () => localStorage.getItem(COMET_DEBUGGER_MODE_KEY) === "true",
  );

  // Keyboard shortcut handler for debugger mode: Meta/Ctrl + c + .
  useEffect(() => {
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
        setShowAppDebugInfo((prev) => {
          const newValue = !prev;
          localStorage.setItem(COMET_DEBUGGER_MODE_KEY, String(newValue));
          return newValue;
        });
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
  }, []);

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
