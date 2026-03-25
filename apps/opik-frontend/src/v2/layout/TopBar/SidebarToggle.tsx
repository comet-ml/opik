import React from "react";
import { Link } from "@tanstack/react-router";
import { PanelLeftOpen } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import Logo from "@/v2/layout/Logo/Logo";

const HOME_PATH = "/$workspaceName/home";

type SidebarToggleProps = {
  onToggle: () => void;
};

const SidebarToggle: React.FunctionComponent<SidebarToggleProps> = ({
  onToggle,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const LogoComponent = usePluginsStore((state) => state.Logo);

  const logo = LogoComponent ? (
    <LogoComponent expanded={false} />
  ) : (
    <Logo expanded={false} />
  );

  return (
    <>
      <Link
        to={HOME_PATH}
        className="block shrink-0"
        params={{ workspaceName }}
      >
        {logo}
      </Link>
      <Button
        variant="ghost"
        size="icon-xs"
        onClick={onToggle}
        className="shrink-0"
      >
        <PanelLeftOpen className="size-4" />
      </Button>
      <Separator orientation="vertical" className="mx-1 h-5" />
    </>
  );
};

export default SidebarToggle;
