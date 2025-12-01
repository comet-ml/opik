import React from "react";
import {
  Book,
  GraduationCap,
  LifeBuoy,
  MessageCircleQuestion,
} from "lucide-react";
import SlackIcon from "@/icons/slack.svg?react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { buildDocsUrl, cn } from "@/lib/utils";
import { SLACK_LINK } from "@/components/pages-shared/onboarding/IntegrationExplorer/components/HelpLinks";

interface SupportHubDropdownProps {
  expanded: boolean;
  openQuickstart: () => void;
  openProvideFeedback: () => void;
}

const SupportHubDropdown: React.FC<SupportHubDropdownProps> = ({
  expanded,
  openQuickstart,
  openProvideFeedback,
}) => {
  const menuContent = (
    <>
      <DropdownMenuItem asChild>
        <a
          href={buildDocsUrl()}
          target="_blank"
          rel="noreferrer"
          className="flex cursor-pointer items-center gap-2"
        >
          <Book className="size-4" />
          <span>Documentation</span>
        </a>
      </DropdownMenuItem>
      <DropdownMenuItem
        onClick={openQuickstart}
        className="flex cursor-pointer items-center gap-2"
      >
        <GraduationCap className="size-4" />
        <span>Quickstart guide</span>
      </DropdownMenuItem>
      <DropdownMenuSeparator />
      <DropdownMenuItem
        onClick={openProvideFeedback}
        className="flex cursor-pointer items-center gap-2"
      >
        <MessageCircleQuestion className="size-4" />
        <span>Provide feedback</span>
      </DropdownMenuItem>
      <DropdownMenuItem asChild>
        <a
          href={SLACK_LINK}
          target="_blank"
          rel="noreferrer"
          className="flex cursor-pointer items-center gap-2"
        >
          <SlackIcon className="size-4" />
          <span>Get help in Slack</span>
        </a>
      </DropdownMenuItem>
    </>
  );

  if (expanded) {
    return (
      <li className="flex">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              className={cn(
                "comet-body-s flex h-8 w-full items-center gap-2 rounded-md text-muted-slate hover:bg-primary-foreground",
                "px-2.5",
              )}
            >
              <LifeBuoy className="size-4 shrink-0" />
              <span className="grow truncate text-left">Support hub</span>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="right" align="end" className="w-56">
            {menuContent}
          </DropdownMenuContent>
        </DropdownMenu>
      </li>
    );
  }

  return (
    <li className="flex">
      <DropdownMenu>
        <TooltipWrapper content="Support hub" side="right">
          <DropdownMenuTrigger asChild>
            <button
              className={cn(
                "comet-body-s flex h-8 w-8 items-center justify-center rounded-md text-muted-slate hover:bg-primary-foreground",
              )}
            >
              <LifeBuoy className="size-4 shrink-0" />
            </button>
          </DropdownMenuTrigger>
        </TooltipWrapper>
        <DropdownMenuContent side="right" align="end" className="w-56">
          {menuContent}
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  );
};

export default SupportHubDropdown;
