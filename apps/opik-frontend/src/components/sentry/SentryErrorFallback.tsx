import { FallbackRender } from "@sentry/react";
import React from "react";

import somethingWentWrongImage from "/images/something-went-wrong.png";
import { Button } from "@/components/ui/button";

const SentryErrorFallback: FallbackRender = ({ resetError }) => (
  <div className="flex size-full flex-col justify-center items-center gap-5 bg-[url('/images/circle-pattern.png')] bg-cover bg-center">
    <img
      src={somethingWentWrongImage}
      alt="Something went wrong"
      width="274px"
    />

    <h2 className="comet-title-l">Something went wrong</h2>

    <div className="comet-body-s flex max-w-xl flex-col text-muted-slate text-center gap-4">
      <p>
        We are sorry for the inconvenience. This error has been reported. If you
        have any urgent issues, please{" "}
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
          <a
            href="https://chat.comet.com/?_gl=1*1npejpb*_gcl_au*MTI1NjUyMDAyMi4xNzM4ODI4NzUy*_ga*Njg0Mjc0MDc4LjE3Mzg4Mjg3NTM.*_ga_26JT894Z2E*MTczOTg4NjAwNC4xNi4xLjE3Mzk4ODYxNjguNTMuMC4w"
            target="_blank"
            rel="noreferrer"
          >
            Slack community
          </a>
        </Button>{" "}
        to get help with bugs and questions.
      </p>
    </div>

    {/*ALEX*/}
    <Button onClick={resetError}>Back to home</Button>
  </div>
);

export default SentryErrorFallback;
