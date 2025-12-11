import React, { useMemo } from "react";
import isString from "lodash/isString";
import { Alert, ALERT_EVENT_TYPE, ALERT_TYPE } from "@/types/alerts";
import useWebhookExamplesQuery from "@/api/alerts/useWebhookExamplesQuery";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";
import Loader from "@/components/shared/Loader/Loader";
import { safelyParseJSON } from "@/lib/utils";
import { applyFieldReplacements } from "@/components/pages/AlertsPage/AddEditAlertPage/helpers";

type WebhookPayloadExampleProps = {
  eventType: ALERT_EVENT_TYPE;
  alertType?: ALERT_TYPE;
  actionButton?: React.ReactNode;
  alert?: Partial<Alert>;
};

const WebhookPayloadExample: React.FunctionComponent<
  WebhookPayloadExampleProps
> = ({ eventType, alertType, actionButton, alert }) => {
  const { data: examples, isPending } = useWebhookExamplesQuery({
    alertType,
  });

  const payload = useMemo(() => {
    if (!examples || !examples.response_examples?.[eventType]) {
      return "";
    }

    return isString(examples.response_examples[eventType])
      ? safelyParseJSON(examples.response_examples[eventType] as string)
      : examples.response_examples[eventType];
  }, [examples, eventType]);

  const formattedPayload = useMemo(() => {
    let p = payload;

    if (alert && alertType) {
      p = applyFieldReplacements(payload, alert, alertType);
    }

    return JSON.stringify(p, null, 2);
  }, [alert, alertType, payload]);

  if (isPending) {
    return <Loader className="min-h-32" />;
  }

  if (!formattedPayload) {
    return (
      <div className="comet-body-s rounded-md border bg-primary-foreground p-3 text-muted-foreground">
        No example available for this event type
      </div>
    );
  }

  return (
    <div className="rounded-md border border-border bg-primary-foreground">
      <div className="flex h-10 items-center justify-between border-b border-border px-4">
        <span className="text-light-slate">Payload</span>
        {actionButton && <div>{actionButton}</div>}
      </div>
      <CodeHighlighter
        data={formattedPayload}
        copyData={formattedPayload}
        language={SUPPORTED_LANGUAGE.json}
      />
    </div>
  );
};

export default WebhookPayloadExample;
