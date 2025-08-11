import React from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import {
  ExternalLink,
  PlayCircle,
  MonitorPlay,
  Book,
  UserPlus,
  Send,
  Blocks,
  MousePointerClick,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";

const inviteTeamMemberSchema = z.object({
  emails: z
    .string()
    .min(1, "Please enter at least one email address")
    .refine((value) => {
      // Improved email validation for comma-separated emails
      const emails = value
        .split(",")
        .map((email) => email.trim())
        .filter(Boolean);
      if (emails.length === 0) return false;

      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      return emails.every((email) => emailRegex.test(email));
    }, "Please enter valid email addresses separated by commas")
    .refine((value) => {
      // Check for empty emails after trimming
      const emails = value
        .split(",")
        .map((email) => email.trim())
        .filter(Boolean);
      return emails.length > 0;
    }, "Please remove any empty email entries"),
});

type InviteTeamMemberFormData = z.infer<typeof inviteTeamMemberSchema>;

type HelpGuideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const HelpGuideDialog: React.FunctionComponent<HelpGuideDialogProps> = ({
  open,
  setOpen,
}) => {
  const form = useForm<InviteTeamMemberFormData>({
    resolver: zodResolver(inviteTeamMemberSchema),
    defaultValues: {
      emails: "",
    },
  });

  const onSubmit = (data: InviteTeamMemberFormData) => {
    console.log("Team member invitation data:", data);
    // TODO: Implement actual invitation logic
    form.reset();
    // Could add success toast here if needed
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) {
      form.reset();
    }
  };

  const handleTryPlayground = () => {
    console.log("Navigate to playground");
    // TODO: Navigate to playground page
  };

  const handleExploreDemoProject = () => {
    console.log("Navigate to demo project");
    // TODO: Navigate to demo project
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[790px]">
        <DialogHeader>
          <DialogTitle>Help guide</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody>
          <div className="comet-body-s mb-3 pb-2 text-muted-slate">
            Need help getting started? Find useful resources here, or{" "}
            <a
              href="https://placeholder-docs-link.com"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              check our docs
              <ExternalLink className="size-3" />
            </a>
            .
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="rounded-lg border bg-card p-4">
              <div className="mb-4 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <MonitorPlay className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    Watch our guided tutorial
                  </div>
                </div>

                <div className="comet-body-s text-muted-slate">
                  Watch a short video to guide you through setup and key
                  features.
                </div>
              </div>

              <div className="relative flex aspect-video items-center justify-center rounded-lg border border-muted-foreground/25 bg-muted">
                <div className="text-center">
                  <PlayCircle className="mx-auto mb-2 size-16 text-muted-foreground/50" />
                  <p className="comet-body-s text-muted-foreground">
                    Video tutorial placeholder
                  </p>
                </div>
              </div>
            </div>

            <div className="rounded-lg border bg-card p-4">
              <div className="mb-2 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <Book className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    Explore our documentation
                  </div>
                </div>
                <div className="comet-body-s text-muted-slate">
                  Check out our docs and helpful guides to get started.
                </div>
              </div>

              <div className="space-y-2">
                <a
                  href="https://placeholder-getting-started.com"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline"
                >
                  Getting started with Opik
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://placeholder-integration-guide.com"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline"
                >
                  Integrate Opik with your LLM application
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://placeholder-observability-guide.com"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline"
                >
                  Adding Opik observability to your codebase
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href="https://placeholder-faq.com"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline"
                >
                  FAQ
                  <ExternalLink className="size-4" />
                </a>
              </div>
            </div>
          </div>

          <div className="rounded-lg border bg-card p-4">
            <div className="mb-4 flex flex-col items-start gap-1.5">
              <div className="flex items-center gap-2">
                <UserPlus className="size-4 text-muted-slate" />
                <div className="comet-body-s-accented">
                  Invite a team member
                </div>
              </div>
              <div className="comet-body-s text-muted-slate">
                Invite teammates by email or username to join your workspace and
                help with setup. Use commas to invite multiple people at once.
              </div>
            </div>

            <Form {...form}>
              <form
                onSubmit={form.handleSubmit(onSubmit)}
                className="space-y-3"
              >
                <div className="flex gap-3">
                  <FormField
                    control={form.control}
                    name="emails"
                    render={({ field }) => (
                      <FormItem className="flex-1">
                        <FormLabel className="sr-only">
                          Email addresses
                        </FormLabel>
                        <FormControl>
                          <Input
                            {...field}
                            placeholder="Type emails or usernames separated by commas"
                            className="h-8"
                            tabIndex={-1}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <Button
                    type="submit"
                    disabled={
                      !form.formState.isValid || form.formState.isSubmitting
                    }
                    className="shrink-0"
                    size="sm"
                  >
                    <Send className="mr-2 size-3.5" />
                    Send invite
                  </Button>
                </div>
              </form>
            </Form>
          </div>

          <Separator className="my-6" />

          <div>
            <h3 className="comet-title-s mb-2">Not ready to integrate yet?</h3>
            <p className="comet-body-s mb-4 py-2 text-muted-slate">
              Explore Opik by testing things out in the playground or browsing
              our Demo project. No setup required.
            </p>

            <div className="mt-4 flex gap-3">
              <Button
                variant="outline"
                onClick={handleTryPlayground}
                className="flex-1"
              >
                <Blocks className="mr-2 size-4" />
                Try our Playground
              </Button>
              <Button
                variant="outline"
                onClick={handleExploreDemoProject}
                className="flex-1"
              >
                <MousePointerClick className="mr-2 size-4" />
                Explore our Demo project
              </Button>
            </div>
          </div>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default HelpGuideDialog;
