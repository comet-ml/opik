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
import { z } from "zod";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { buildDocsUrl } from "@/lib/utils";
import useSendOnboardingEmailMutation from "@/hooks/useSendOnboardingEmailMutation";
import { trackEvent, OpikEvent } from "@/lib/analytics/tracking";
import { ConnectIllustration } from "./illustrations";

// Form state (email, emailSent) is owned by the parent: step panels remount
// to replay their entrance animations, and lifted state survives the remount.
interface ConnectStepProps {
  email: string;
  onEmailChange: (value: string) => void;
  emailSent: boolean;
  onEmailSentChange: (sent: boolean) => void;
}

const emailSchema = z.string().email();
const isValidEmail = (value: string) => emailSchema.safeParse(value).success;

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

const ConnectStep: React.FC<ConnectStepProps> = ({
  email,
  onEmailChange,
  emailSent,
  onEmailSentChange,
}) => {
  const { mutate, isPending, isAvailable } = useSendOnboardingEmailMutation();
  const [flyRect, setFlyRect] = useState<{
    top: number;
    left: number;
    width: number;
    height: number;
  }>();
  const illustrationRef = useRef<HTMLDivElement>(null);

  const handleSendEmail = () => {
    if (!isValidEmail(email) || !isAvailable) return;
    mutate(email, {
      onSuccess: () => {
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
        onEmailSentChange(true);
      },
    });
  };

  return (
    <>
      <div
        className="slide-fade-right"
        style={flyRect ? { height: flyRect.height } : undefined}
      >
        {!flyRect && (
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
            onAnimationEnd={() => setFlyRect(undefined)}
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
          Continue on desktop to start sending traces to Opik.{" "}
          {isAvailable
            ? "We'll email you everything you need to get started."
            : "Check out our quickstart guide for everything you need to get started."}
        </p>
      </div>

      {isAvailable ? (
        <div className="slide-fade-right flex flex-col gap-2 rounded-md border border-dashed border-primary/60 bg-primary-100/60 p-3 [animation-delay:200ms] dark:bg-primary/10">
          <div className="px-0.5">
            <p className="text-sm font-medium text-foreground">
              Get the instructions
            </p>
          </div>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleSendEmail();
            }}
            className="flex flex-col gap-2"
          >
            <Input
              type="email"
              placeholder="your@email.com"
              value={email}
              onChange={(e) => {
                onEmailChange(e.target.value);
                if (emailSent) onEmailSentChange(false);
              }}
              dimension="sm"
              autoComplete="email"
              inputMode="email"
              autoCapitalize="none"
            />

            <Button
              size="lg"
              type="submit"
              disabled={!isValidEmail(email) || emailSent || isPending}
              className="gap-1.5"
            >
              {isPending ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : emailSent ? (
                <Check className="size-3.5" />
              ) : (
                <Mail className="size-3.5" />
              )}
              {/* Wrap the state-dependent label in a <span> so React swaps a
                  stable wrapper element instead of a bare text node. Under
                  browser translation Google Translate re-parents text nodes
                  into <font> elements; reconciling this label (icon + text swap
                  on submit) against a re-parented bare text node throws
                  "NotFoundError: removeChild". The wrapper keeps the label fully
                  translatable (unlike translate="no"). See facebook/react#11538. */}
              <span>
                {isPending
                  ? "Sending..."
                  : emailSent
                    ? "Instructions sent!"
                    : "Email setup instructions"}
              </span>
            </Button>
          </form>

          <div className="flex items-center gap-1.5 py-0.5 opacity-60">
            <div className="h-px flex-1 bg-border" />
            <span className="text-[8px] leading-[10px] text-muted-slate">
              OR
            </span>
            <div className="h-px flex-1 bg-border" />
          </div>

          <Button variant="outline" size="lg" asChild>
            <a
              href={buildDocsUrl("/quickstart")}
              target="_blank"
              rel="noopener noreferrer"
              className="gap-1.5"
            >
              Open docs
              <ArrowUpRight className="size-4" />
            </a>
          </Button>
        </div>
      ) : (
        <Button
          size="lg"
          className="slide-fade-right gap-1.5 [animation-delay:200ms]"
          asChild
        >
          <a
            href={buildDocsUrl("/quickstart")}
            target="_blank"
            rel="noopener noreferrer"
          >
            Quickstart guide
            <ArrowUpRight className="size-4" />
          </a>
        </Button>
      )}

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
            label="Works with Claude Code, Cursor, Codex, and more"
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
