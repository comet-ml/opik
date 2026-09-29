import React from "react";
import { Coins } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import usePluginsStore from "@/store/PluginsStore";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Separator } from "@/ui/separator";

type OutOfCreditsButtonProps = {
  label: string;
  description: string;
  large?: boolean;
};

const OutOfCreditsButton: React.FC<OutOfCreditsButtonProps> = ({
  label,
  description,
  large = false,
}) => {
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  return (
    <HoverCard openDelay={100}>
      <HoverCardTrigger asChild>
        <Button
          variant="outline"
          size={large ? "default" : "2xs"}
          className={cn(
            "cursor-default bg-[var(--tag-yellow-bg)] text-[var(--tag-yellow-text)] hover:bg-[var(--tag-yellow-bg)] hover:text-[var(--tag-yellow-text)] active:bg-[var(--tag-yellow-bg)] active:text-[var(--tag-yellow-text)]",
            "dark:bg-muted-disabled dark:text-chart-yellow dark:hover:bg-muted-disabled dark:hover:text-chart-yellow dark:active:bg-muted-disabled",
            large ? "gap-2" : "gap-1.5",
          )}
        >
          <Coins className={large ? "size-4" : "size-3.5"} />
          {label}
        </Button>
      </HoverCardTrigger>
      <HoverCardContent align="center" className="w-[278px] p-1">
        <div className="flex items-center gap-1 p-1">
          <span className="flex size-4 shrink-0 items-center justify-center rounded-md bg-chart-yellow">
            <Coins className="size-3 text-black" />
          </span>
          <span className="comet-body-xs-accented text-foreground">
            Out of Ollie credits
          </span>
        </div>
        <Separator className="my-1" />
        <p className="comet-body-xs px-1 text-muted-slate">{description}</p>
        {BillingLink && <BillingLink label="View billing" variant="popover" />}
      </HoverCardContent>
    </HoverCard>
  );
};

export default OutOfCreditsButton;
