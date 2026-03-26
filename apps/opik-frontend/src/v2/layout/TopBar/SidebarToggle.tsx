import React from "react";
import { Link } from "@tanstack/react-router";
import { PanelLeft } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import Logo from "@/shared/Logo/Logo";

const HOME_PATH = "/$workspaceName/home";

type SidebarToggleProps = {
  onToggle: () => void;
};

const SidebarToggle: React.FunctionComponent<SidebarToggleProps> = ({
  onToggle,
}) => {
  const workspaceName = useActiveWorkspaceName();

  const logo = <Logo expanded={false} />;

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
        size="icon-2xs"
        onClick={onToggle}
        className="shrink-0"
      >
        <PanelLeft className="size-4" />
      </Button>
      <Separator orientation="vertical" className="mx-1 h-5" />
    </>
  );
};

export default SidebarToggle;
