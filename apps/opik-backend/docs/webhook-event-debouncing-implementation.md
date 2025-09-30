# Webhook Event Debouncing Implementation

## Overview

This document describes the implementation of webhook event debouncing and aggregation based on the Alerting MVP design document. The implementation provides a mechanism to aggregate multiple webhook events within a configurable time window and send consolidated notifications.

## Architecture

### Key Components

1. **WebhookEventAggregationService**
   - **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookEventAggregationService`
   - **Purpose**: Manages event aggregation with timestamp-based debouncing in Redis
   - **Key Features**:
     - Stores events with first-seen timestamp
     - Groups events by alert ID and event type
     - Checks if events are ready to be published based on debouncing window
     - Key pattern: `webhook_pending:{alertId}:{eventType}`

2. **WebhookBucketProcessorJob**
   - **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookBucketProcessorJob`
   - **Purpose**: Scheduled job that runs every 5 seconds to check for pending events
   - **Key Features**:
     - Runs as a Quartz job with `@Every("5s")` annotation
     - Prevents concurrent execution with `@DisallowConcurrentExecution`
     - Processes events that have exceeded the debouncing window
     - Continues processing even if individual events fail

3. **WebhookBucketProcessor**
   - **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookBucketProcessor`
   - **Purpose**: Sends consolidated webhook notifications
   - **Key Features**:
     - Creates consolidated events with aggregated event IDs
     - Sends one notification per alert+event type combination
     - Includes metadata about aggregation (event count, event IDs)

4. **WebhookConfig.DebouncingConfig**
   - **Location**: `com.comet.opik.infrastructure.WebhookConfig.DebouncingConfig`
   - **Purpose**: Configuration for debouncing behavior
   - **Properties**:
     - `enabled`: Enable/disable debouncing (default: `true`)
     - `windowSize`: Debouncing time window (default: 60 seconds)
     - `bucketTtl`: TTL for pending events in Redis (default: 3 minutes)

## Data Flow

```
┌─────────────────┐
│  Webhook Event  │
│   Triggered     │
└────────┬────────┘
         │
         ▼
┌─────────────────────────┐
│  WebhookSubscriber      │
│  (aggregateEvent)       │
└────────┬────────────────┘
         │
         ▼
┌──────────────────────────────────────┐
│  WebhookEventAggregationService      │
│  Store event with timestamp in Redis │
│  Key: webhook_pending:{alertId}:     │
│       {eventType}                    │
│  Data:                               │
│    - event_timestamps: first_seen    │
│    - eventIds: [id1, id2, ...]       │
└──────────────────────────────────────┘
         │
         │  (Background Job - Every 5 seconds)
         ▼
┌──────────────────────────────────────┐
│  WebhookBucketProcessorJob           │
│  Check for pending events            │
│  If (now - first_seen) >= window:   │
└────────┬─────────────────────────────┘
         │ YES: Ready to publish
         ▼
┌──────────────────────────────────────┐
│  WebhookBucketProcessor              │
│  Create consolidated event           │
│  Send via WebhookHttpClient          │
│  Delete pending key from Redis       │
└──────────────────────────────────────┘
```

## Implementation Details

### Event Storage in Redis

Each unique alert+event type combination gets a Redis key:

```
Key: webhook_pending:{alertId}:{eventType}
Value: Hash Map {
  "event_timestamps": "1704067200000",  // First seen timestamp in milliseconds
  "eventIds": "[\"event-123\", \"event-456\", \"event-789\"]"  // JSON array of event IDs
}
TTL: 3 minutes (configurable)
```

### Debouncing Logic

1. **Event Arrives**: When a new event arrives:
   - Check if pending key exists
   - If **first event**: Store current timestamp as `event_timestamps`
   - If **subsequent event**: Keep original timestamp, add event ID to list

2. **Background Check** (Every 5 seconds):
   - Scan all keys matching `webhook_pending:*`
   - For each key:
     - Get `event_timestamps` value
     - Calculate: `elapsed = now - event_timestamps`
     - If `elapsed >= windowSize`: Mark as ready to publish

3. **Publishing**:
   - Create consolidated webhook event with:
     - All aggregated event IDs
     - Event count
     - Aggregation metadata
   - Send via `WebhookHttpClient`
   - Delete pending key from Redis

### Configuration Example

```yaml
webhook:
  enabled: true
  debouncing:
    enabled: true
    windowSize: 60s        # Wait 60 seconds before publishing
    bucketTtl: 3m          # Keep pending events for max 3 minutes
```

## Benefits

1. **Reduced Webhook Calls**: Multiple events are consolidated into single notifications
2. **Configurable Window**: Adjust debouncing window based on requirements
3. **Non-Blocking**: Events are stored immediately, processing happens asynchronously
4. **Fault Tolerant**: Individual failures don't affect other events
5. **Scalable**: Background job frequency (5s) is independent of debouncing window

## Example Scenarios

### Scenario 1: Single Event
```
Time 0s:  Event A arrives → Store with timestamp
Time 5s:  Background check → Not ready (5s < 60s)
Time 10s: Background check → Not ready (10s < 60s)
...
Time 60s: Background check → Ready! → Publish 1 event → Delete key
```

### Scenario 2: Multiple Events Within Window
```
Time 0s:  Event A arrives → Store with timestamp T0
Time 5s:  Background check → Not ready
Time 10s: Event B arrives → Add to same key (keeps timestamp T0)
Time 15s: Event C arrives → Add to same key (keeps timestamp T0)
...
Time 60s: Background check → Ready! → Publish consolidated (3 events) → Delete key
```

### Scenario 3: Events Spanning Multiple Windows
```
Time 0s:  Event A arrives → Store with timestamp T0
Time 65s: Event B arrives (after 65s)
          - Previous key published at Time 60s
          - New key created with timestamp T65
Time 125s: Event B published (T125 - T65 = 60s)
```

## Testing

### Unit Tests

Test file: `WebhookEventAggregationTest.java`

Tests cover:
- Event aggregation with single and multiple events
- Timestamp tracking (first event sets timestamp)
- Debouncing window validation
- Pending key retrieval and filtering
- Event ID serialization/deserialization

### Integration Tests

Required tests (to be implemented):
- End-to-end flow with real Redis
- Multiple concurrent events
- TTL expiration behavior
- Job scheduling and execution

## Known Limitations & Future Enhancements

1. **Alert Configuration**: Currently uses placeholder values for:
   - Workspace ID
   - Webhook URL
   - Custom headers
   - **TODO**: Integrate with alert management service

2. **Deprecation Warning**: `getKeysByPattern()` is deprecated in Redisson
   - **TODO**: Update to use non-deprecated API

3. **Monitoring**: Add metrics for:
   - Events aggregated count
   - Publish latency
   - Failed publications

4. **Rate Limiting**: Consider adding:
   - Maximum events per pending key
   - Maximum pending keys per workspace

## Configuration Tuning

### Recommended Settings

For different use cases:

**High-Frequency Events (e.g., error tracking)**:
```yaml
debouncing:
  windowSize: 30s   # Shorter window for faster notifications
  bucketTtl: 2m
```

**Low-Frequency Events (e.g., daily reports)**:
```yaml
debouncing:
  windowSize: 5m    # Longer window for better aggregation
  bucketTtl: 10m
```

**Real-Time Alerts (disable debouncing)**:
```yaml
debouncing:
  enabled: false    # Immediate webhook for every event
```

## Related Documentation

- [Alerting MVP High-Level Design Document](https://www.notion.so/cometml/Alerting-MVP-High-Level-Design-Document-2387124010a38003b6ffc619fddcafe1)
- [Webhook Configuration Guide](./webhook-configuration.md)
- [Redis Configuration Guide](./redis-configuration.md)

## Version History

| Version | Date | Author | Description |
|---------|------|--------|-------------|
| 1.0     | 2025-01-30 | System | Initial implementation with timestamp-based debouncing |
