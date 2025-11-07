import { useDebugStore } from "@/store/DebugStore";
import { DropdownMenuItem } from "@/components/ui/dropdown-menu";
import { SquareMousePointerIcon } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Switch } from "@/components/ui/switch";

export const COMET_DEBUGGER_MODE_KEY = "comet-debugger-mode"; // Same in EM

const UserMenuAppDebugInfoToggle = () => {
  const showAppDebugInfo = useDebugStore((state) => state.showAppDebugInfo);
  const setShowAppDebugInfo = useDebugStore(
    (state) => state.setShowAppDebugInfo,
  );

  const handleToggle = (event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    const shouldShowAppDebugInfo = !showAppDebugInfo;

    localStorage.setItem(
      COMET_DEBUGGER_MODE_KEY,
      String(shouldShowAppDebugInfo).toLowerCase(),
    );
    setShowAppDebugInfo(shouldShowAppDebugInfo);
  };

  return (
    <DropdownMenuItem
      className="min-w-52 flex-row justify-between"
      onClick={handleToggle}
    >
      <div className="flex items-center">
        <SquareMousePointerIcon className="mr-2 size-4" />
        <TooltipWrapper content="With this option enabled, you will see app debugging information">
          <span className="comet-body-s truncate text-foreground">
            Debugger
          </span>
        </TooltipWrapper>
      </div>
      <Switch
        size="xs"
        checked={showAppDebugInfo}
        onCheckedChange={setShowAppDebugInfo}
      />
    </DropdownMenuItem>
  );
};

export default UserMenuAppDebugInfoToggle;
