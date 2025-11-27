import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import get from "lodash/get";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import Loader from "@/components/shared/Loader/Loader";

type EventsListProps = {
  data: Trace | Span;
  isLoading: boolean;
  search?: string;
};

const METADATA_EVENTS_KEY = "opentelemetry.events";

/**
 * Raw event type as received from OpenTelemetry.
 * Contains time_unix_nano in nanoseconds since Unix epoch.
 */
export type RawEventType = {
  name: string;
  time_unix_nano: number;
  attributes?: Record<string, unknown>;
};

/**
 * Processed event type with ISO timestamp.
 * Used for display in the UI.
 */
export type EventTypeWithTimestamp = {
  name: string;
  timestamp: string;
  attributes?: Record<string, unknown>;
};

/**
 * Converts a raw OpenTelemetry event to an event with ISO timestamp.
 *
 * @param rawEvent - The raw event from OpenTelemetry with time_unix_nano
 * @returns The processed event with ISO timestamp string
 */
const mapRawEventToEventWithTimestamp = (
  rawEvent: RawEventType,
): EventTypeWithTimestamp => {
  // Convert nanoseconds to milliseconds for Date constructor
  const timestampMs = rawEvent.time_unix_nano / 1_000_000;
  const timestamp = new Date(timestampMs).toISOString();

  return {
    name: rawEvent.name,
    timestamp,
    attributes: rawEvent.attributes,
  };
};

const EventsList: React.FC<EventsListProps> = ({ data, isLoading, search }) => {
  // Check if events exist in metadata and map them to processed events
  const rawEvents = get(data.metadata, METADATA_EVENTS_KEY);

  const events = useMemo(() => {
    if (!rawEvents || !Array.isArray(rawEvents)) return [];
    return (rawEvents as RawEventType[])
      .filter((event): event is RawEventType => {
        // Type guard to ensure the event has required fields
        return (
          typeof event === "object" &&
          event !== null &&
          "name" in event &&
          "time_unix_nano" in event &&
          typeof event.name === "string" &&
          typeof event.time_unix_nano === "number"
        );
      })
      .map(mapRawEventToEventWithTimestamp);
  }, [rawEvents]);

  if (events.length === 0) {
    return null;
  }

  return (
    <AccordionItem value="events" disabled={isLoading}>
      <AccordionTrigger>Events</AccordionTrigger>
      <AccordionContent>
        {isLoading ? (
          <Loader />
        ) : (
          <SyntaxHighlighter data={events} search={search} withSearch />
        )}
      </AccordionContent>
    </AccordionItem>
  );
};

export default EventsList;
