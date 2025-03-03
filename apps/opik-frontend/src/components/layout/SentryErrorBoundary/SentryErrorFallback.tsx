import React from "react";
import { FallbackRender } from "@sentry/react";

import somethingWentWrongImage from "/images/something-went-wrong.png";
import { Button } from "@/components/ui/button";

const SentryErrorFallback: FallbackRender = ({ resetError }) => {
  return (
    <div className="flex size-full flex-col items-center justify-center gap-5 bg-[url('/images/circle-pattern.png')] bg-cover bg-center">
      <img
        src={somethingWentWrongImage}
        alt="Something went wrong"
        width="274px"
      />

      <h2 className="comet-title-l">Something went wrong</h2>

      <div className="comet-body-s flex max-w-xl flex-col gap-4 text-center text-muted-slate">
        <p>
          We are sorry for the inconvenience. This error has been reported. If
          you have any urgent issues, please{" "}
          <Button variant="link" size="sm" asChild className="inline px-0">
            <a href="mailto:support@comet.com">contact us</a>
          </Button>{" "}
          directly.
        </p>
        <p>
          You can also submit feature requests to our{" "}
          <Button variant="link" size="sm" asChild className="inline px-0">
            <a
              href="https://github.com/comet-ml/opik"
              target="_blank"
              rel="noreferrer"
            >
              GitHub repository
            </a>
          </Button>
          , or join our{" "}
          <Button variant="link" size="sm" asChild className="inline px-0">
            <a href="https://chat.comet.com" target="_blank" rel="noreferrer">
              Slack community
            </a>
          </Button>{" "}
          to get help with bugs and questions.
        </p>
      </div>

      <Button onClick={resetError}>Continue</Button>
    </div>
  );
};

export default SentryErrorFallback;
