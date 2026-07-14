import React, { useEffect, useState } from "react";
import { Plus, Mic, ArrowUp } from "lucide-react";
import { WelcomeIllustration } from "./illustrations";

const TYPING_TEXT = "What is a trace in Opik?";
const TYPE_SPEED = 80;
const DELETE_SPEED = 30;
const PAUSE_AFTER_TYPE = 2500;
const PAUSE_AFTER_DELETE = 500;
const TYPING_START_DELAY = 500;

const TypingText: React.FC = () => {
  const [text, setText] = useState("");

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    let pos = 0;
    let deleting = false;

    const tick = () => {
      if (!deleting) {
        if (pos < TYPING_TEXT.length) {
          pos++;
          setText(TYPING_TEXT.slice(0, pos));
          timer = setTimeout(tick, TYPE_SPEED);
        } else {
          timer = setTimeout(() => {
            deleting = true;
            tick();
          }, PAUSE_AFTER_TYPE);
        }
      } else {
        if (pos > 0) {
          pos--;
          setText(TYPING_TEXT.slice(0, pos));
          timer = setTimeout(tick, DELETE_SPEED);
        } else {
          deleting = false;
          timer = setTimeout(tick, PAUSE_AFTER_DELETE);
        }
      }
    };

    const startTimer = setTimeout(tick, TYPING_START_DELAY);
    return () => {
      clearTimeout(startTimer);
      clearTimeout(timer);
    };
  }, []);

  return (
    <>
      {text}
      <span className="ml-px inline-block h-[15px] w-[1.5px] translate-y-px animate-caret-blink bg-foreground" />
    </>
  );
};

const WelcomeStep: React.FC = () => {
  return (
    <>
      <div className="slide-fade-left">
        <WelcomeIllustration />
      </div>

      <div className="flex flex-col gap-1.5 px-0.5">
        <h1 className="slide-fade-left text-lg font-medium leading-7 text-foreground [animation-delay:75ms]">
          Welcome to Opik
        </h1>
        <p className="slide-fade-left pb-2 text-sm leading-5 text-muted-slate [animation-delay:150ms]">
          See what happens behind the scenes every time a user interacts with
          your AI agent or app. Track prompts, tool calls, latency, cost, and
          failures, all in one place.
        </p>
      </div>

      <div className="slide-fade-left flex flex-col gap-2 rounded-lg border border-dashed border-border bg-soft-background p-5 shadow-sm [animation-delay:200ms] dark:bg-accent-background">
        <div className="px-0.5 pb-0.5 pt-1">
          <p className="text-center text-sm font-medium leading-[18px] text-foreground">
            What can I help you with?
          </p>
        </div>

        <div>
          <div className="flex flex-col gap-2 rounded-xl border border-border bg-background px-4 pb-3 pt-4">
            <div className="min-h-5 text-sm leading-5 text-foreground">
              <TypingText />
            </div>
            <div className="flex items-center justify-between">
              <Plus className="size-3.5 text-slate-400" />
              <div className="flex items-center gap-3">
                <Mic className="size-3.5 text-slate-400" />
                <div className="flex size-6 items-center justify-center rounded-full bg-foreground">
                  <ArrowUp className="size-3 text-background" />
                </div>
              </div>
            </div>
          </div>
        </div>

        <p className="text-center text-xs leading-[14px] text-slate-400">
          Here&apos;s an example user interaction. We&apos;ll use Opik to
          evaluate and improve the agent underneath.
        </p>
      </div>
    </>
  );
};

export default WelcomeStep;
