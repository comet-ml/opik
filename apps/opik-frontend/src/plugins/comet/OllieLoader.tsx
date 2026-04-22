import React, { useEffect, useState } from "react";

import { cn } from "@/lib/utils";
import OwlArt from "@/shared/OwlArt";

const MESSAGES = [
  "Ollie is waking up\u2026",
  "Loading traces\u2026",
  "Crunching evaluation data\u2026",
  "Almost ready\u2026",
] as const;

const ROTATION_INTERVAL_MS = 2200;

export type OllieLoaderVariant = "page" | "sidebar" | "collapsed";

const OWL_SIZE: Record<OllieLoaderVariant, string> = {
  page: "size-[72px]",
  sidebar: "size-[48px]",
  collapsed: "size-6",
};

interface OllieLoaderProps {
  variant?: OllieLoaderVariant;
}

export function OllieLoader({
  variant = "page",
}: OllieLoaderProps): React.ReactElement {
  const [index, setIndex] = useState(0);
  const showText = variant !== "collapsed";

  useEffect(() => {
    if (!showText) return;
    const id = window.setInterval(() => {
      setIndex((current) => (current + 1) % MESSAGES.length);
    }, ROTATION_INTERVAL_MS);
    return () => window.clearInterval(id);
  }, [showText]);

  const owlSize = OWL_SIZE[variant];

  const wrapperBg = variant === "page" ? "bg-muted" : "border-l bg-muted/40";

  return (
    <div
      className={cn("flex size-full items-center justify-center", wrapperBg)}
    >
      <div className="flex flex-col items-center justify-center gap-2">
        <OwlArt className={owlSize} />
        {showText && (
          <p
            role="status"
            aria-live="polite"
            className={cn(
              "text-center font-code text-foreground",
              variant === "page"
                ? "text-[16px] leading-5"
                : "text-[13px] leading-4",
            )}
          >
            <span
              key={index}
              className="inline-block motion-safe:animate-ollie-text-in"
            >
              {MESSAGES[index]}
            </span>
          </p>
        )}
      </div>
    </div>
  );
}

export default OllieLoader;
