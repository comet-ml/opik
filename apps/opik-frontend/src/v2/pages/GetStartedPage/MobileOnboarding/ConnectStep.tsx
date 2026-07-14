import React, { useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  Mail,
  ArrowUpRight,
  Zap,
  Bot,
  Puzzle,
  Loader2,
  Check,
} from "lucide-react";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { buildDocsUrl } from "@/lib/utils";
import cometApi from "@/plugins/comet/api";
import { trackEvent, OpikEvent } from "@/lib/analytics/tracking";
import { ConnectIllustration } from "./illustrations";

interface ConnectStepProps {
  userEmail?: string;
}

const isValidEmail = (value: string) =>
  /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);

const BenefitCard: React.FC<{
  icon: React.ElementType;
  iconClass: string;
  bgClass: string;
  label: string;
  delayMs: number;
}> = ({ icon: Icon, iconClass, bgClass, label, delayMs }) => (
  <div
    className="slide-fade-right flex items-center gap-2 rounded-md border border-border bg-soft-background p-3 dark:bg-accent-background"
    style={{ animationDelay: `${delayMs}ms` }}
  >
    <div
      className={`flex size-4 shrink-0 items-center justify-center rounded ${bgClass}`}
    >
      <Icon className={iconClass} />
    </div>
    <span className="text-xs text-foreground">{label}</span>
  </div>
);

const ConnectStep: React.FC<ConnectStepProps> = ({ userEmail = "" }) => {
  const { toast } = useToast();
  const [email, setEmail] = useState(userEmail);
  const [emailSent, setEmailSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [flyRect, setFlyRect] = useState<{
    top: number;
    left: number;
    width: number;
    height: number;
  }>();
  const illustrationRef = useRef<HTMLDivElement>(null);

  const handleSendEmail = async () => {
    if (!isValidEmail(email)) return;
    setSending(true);
    try {
      await cometApi.post("/opik/onboarding/send-mobile-onboarding-email", {
        email,
      });
      trackEvent(OpikEvent.MOBILE_ONBOARDING_EMAIL_SENT);
      const rect = illustrationRef.current?.getBoundingClientRect();
      if (rect) {
        setFlyRect({
          top: rect.top,
          left: rect.left,
          width: rect.width,
          height: rect.height,
        });
      }
      setEmailSent(true);
    } catch {
      toast({
        title: "Failed to send email",
        description: "Please try again.",
        variant: "destructive",
      });
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      <div
        className="slide-fade-right"
        style={flyRect ? { height: flyRect.height } : undefined}
      >
        {!emailSent && (
          <div ref={illustrationRef}>
            <ConnectIllustration />
          </div>
        )}
      </div>

      {emailSent &&
        flyRect &&
        createPortal(
          <div
            className="illustration-fly pointer-events-none"
            style={{
              position: "fixed",
              zIndex: 9999,
              top: flyRect.top,
              left: flyRect.left,
              width: flyRect.width,
            }}
          >
            <ConnectIllustration />
          </div>,
          document.body,
        )}

      <div className="flex flex-col gap-1.5 px-0.5">
        <h1 className="slide-fade-right text-lg font-medium text-foreground [animation-delay:75ms]">
          Ready to connect your app?
        </h1>
        <p className="slide-fade-right pb-2 text-sm text-muted-slate [animation-delay:150ms]">
          Continue on desktop to start sending traces to Opik. We&apos;ll email
          you everything you need to get started.
        </p>
      </div>

      <div className="slide-fade-right flex flex-col gap-2 rounded-md border border-dashed border-primary/60 bg-primary-100/60 p-3 [animation-delay:200ms] dark:bg-primary/10">
        <div className="px-0.5">
          <p className="text-sm font-medium text-foreground">
            Get the instructions
          </p>
        </div>

        <input
          type="email"
          placeholder="your@email.com"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            if (emailSent) setEmailSent(false);
          }}
          className="h-8 rounded-md border border-border bg-background px-3 text-sm text-foreground outline-none"
        />

        <Button
          size="sm"
          onClick={handleSendEmail}
          disabled={!isValidEmail(email) || emailSent || sending}
          className="gap-1.5"
        >
          {sending ? (
            <Loader2 className="size-3.5 animate-spin" />
          ) : emailSent ? (
            <Check className="size-3.5" />
          ) : (
            <Mail className="size-3.5" />
          )}
          {sending
            ? "Sending..."
            : emailSent
              ? "Instructions sent!"
              : "Email setup instructions"}
        </Button>

        <div className="flex items-center gap-1.5 py-0.5 opacity-60">
          <div className="h-px flex-1 bg-border" />
          <span className="text-[8px] leading-[10px] text-muted-slate">OR</span>
          <div className="h-px flex-1 bg-border" />
        </div>

        <Button variant="outline" size="sm" asChild>
          <a
            href={buildDocsUrl("/quickstart")}
            target="_blank"
            rel="noopener noreferrer"
            className="gap-1.5"
          >
            Open docs
            <ArrowUpRight className="size-3.5" />
          </a>
        </Button>
      </div>

      <div className="flex flex-col gap-1.5 pt-4">
        <p className="slide-fade-right text-sm font-medium leading-[18px] text-foreground [animation-delay:300ms]">
          Why continue on desktop
        </p>

        <div className="flex flex-col gap-2">
          <BenefitCard
            icon={Zap}
            iconClass="size-2.5 dark:text-slate-900"
            bgClass="bg-accent-green"
            label="Send your first trace in under 5 minutes"
            delayMs={375}
          />
          <BenefitCard
            icon={Bot}
            iconClass="size-3 dark:text-slate-900"
            bgClass="bg-accent-blue"
            label="Works with Claude code, Cursor, Codex, and more"
            delayMs={450}
          />
          <BenefitCard
            icon={Puzzle}
            iconClass="size-2.5 text-white"
            bgClass="bg-accent-magenta"
            label="Use one of our 60+ integrations"
            delayMs={525}
          />
        </div>
      </div>
    </>
  );
};

export default ConnectStep;
