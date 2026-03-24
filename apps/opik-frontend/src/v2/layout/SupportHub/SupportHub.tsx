import { useState } from "react";
import {
  Book,
  GraduationCap,
  LifeBuoy,
  MessageCircleQuestion,
} from "lucide-react";
import SlackIcon from "@/icons/slack.svg?react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { buildDocsUrl } from "@/lib/utils";
import { SLACK_LINK } from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/HelpLinks";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import ProvideFeedbackDialog from "@/v2/layout/SideBar/FeedbackDialog/ProvideFeedbackDialog";

const SupportHub = () => {
  const [openProvideFeedback, setOpenProvideFeedback] = useState(false);
  const { open: openQuickstart } = useOpenQuickStartDialog();

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm">
            <LifeBuoy className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
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
            onClick={() => setOpenProvideFeedback(true)}
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
        </DropdownMenuContent>
      </DropdownMenu>

      <ProvideFeedbackDialog
        open={openProvideFeedback}
        setOpen={setOpenProvideFeedback}
      />
    </>
  );
};

export default SupportHub;
