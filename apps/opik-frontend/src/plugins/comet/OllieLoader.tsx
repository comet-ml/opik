import React, { useEffect, useState } from "react";

import { cn } from "@/lib/utils";

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

const OwlArt: React.FC<{ className?: string }> = ({ className }) => (
  <div className={cn("motion-safe:animate-ollie-breathe", className)}>
    <svg
      className="size-full"
      viewBox="0 0 72 72"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden
    >
      <path d="M13.5 27L19.125 15.75L24.75 27" fill="#F46E41" />
      <path d="M47.25 27L52.875 15.75L58.5 27" fill="#F46E41" />
      <g
        className="motion-safe:animate-ollie-blink"
        style={{
          transformOrigin: "center",
          transformBox: "fill-box",
        }}
      >
        <path
          d="M51.75 21.9375C62.0018 21.9375 70.3125 30.2482 70.3125 40.5C70.3125 50.7518 62.0018 59.0625 51.75 59.0625C45.1063 59.0625 39.2797 55.5711 36 50.3242C32.7203 55.5711 26.8937 59.0625 20.25 59.0625C9.99821 59.0625 1.6875 50.7518 1.6875 40.5C1.6875 30.2482 9.99821 21.9375 20.25 21.9375C26.8934 21.9375 32.7202 25.4283 36 30.6748C39.2798 25.4283 45.1066 21.9375 51.75 21.9375ZM20.25 27.5625C13.1048 27.5625 7.3125 33.3548 7.3125 40.5C7.3125 47.6452 13.1048 53.4375 20.25 53.4375C27.3952 53.4375 33.1875 47.6452 33.1875 40.5C33.1875 33.3548 27.3952 27.5625 20.25 27.5625ZM51.75 27.5625C44.6048 27.5625 38.8125 33.3548 38.8125 40.5C38.8125 47.6452 44.6048 53.4375 51.75 53.4375C58.8952 53.4375 64.6875 47.6452 64.6875 40.5C64.6875 33.3548 58.8952 27.5625 51.75 27.5625Z"
          fill="#F46E41"
        />
      </g>
    </svg>
  </div>
);

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
