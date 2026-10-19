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
};

const OutOfCreditsButton: React.FC<OutOfCreditsButtonProps> = ({
  label,
  description,
}) => {
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  return (
    <HoverCard openDelay={100}>
      <HoverCardTrigger asChild>
        <Button
          variant="outline"
          size="2xs"
          className={cn(
            "cursor-default bg-chart-yellow-light text-[var(--tag-yellow-text)] hover:bg-chart-yellow-light hover:text-[var(--tag-yellow-text)] active:bg-chart-yellow-light active:text-[var(--tag-yellow-text)]",
            "dark:text-chart-yellow dark:hover:text-chart-yellow",
            "gap-1.5",
          )}
        >
          <Coins className="size-3.5" />
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
