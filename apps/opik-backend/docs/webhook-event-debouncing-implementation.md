# Webhook Event Debouncing Implementation

## Overview

This document describes the implementation of webhook event debouncing and aggregation based on the Alerting MVP design document. The implementation provides a mechanism to aggregate multiple webhook events within a configurable time window and send consolidated notifications.

## Architecture Principle

**WebhookSubscriber ONLY sends webhooks via HTTP** - it has no knowledge of aggregation or debouncing logic. All event grouping and aggregation happens in Redis, managed by dedicated services.

## Key Components

### 1. WebhookEventStorageService (NEW)
- **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookEventStorageService`
- **Purpose**: Entry point for webhook events - decides whether to aggregate or send immediately
- **Responsibilities**:
  - Receives webhook events from the application
  - If debouncing enabled → stores in Redis via WebhookEventAggregationService
  - If debouncing disabled → sends immediately via WebhookSubscriber
  - Single responsibility: routing events based on configuration

### 2. WebhookEventAggregationService
- **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookEventAggregationService`
- **Purpose**: Manages event aggregation with timestamp-based debouncing in Redis
- **Responsibilities**:
  - Stores events with first-seen timestamp in Redis
  - Groups events by alert ID and event type
  - Checks if events are ready to be published based on debouncing window
  - Key pattern: `webhook_pending:{alertId}:{eventType}`
  - **Does NOT send webhooks** - only manages Redis storage

### 3. WebhookBucketProcessorJob
- **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookBucketProcessorJob`
- **Purpose**: Scheduled job that processes pending events
- **Configuration**: Runs every 5 seconds (`@Every("5s")`)
- **Responsibilities**:
  - Checks Redis for events past debouncing window
  - Processes aggregated events via WebhookBucketProcessor
  - Prevents concurrent execution using `@DisallowConcurrentExecution`
  - Continues processing even if individual events fail

### 4. WebhookBucketProcessor
- **Location**: `com.comet.opik.api.resources.v1.events.webhooks.WebhookBucketProcessor`
- **Purpose**: Creates and sends consolidated webhook notifications
- **Responsibilities**:
  - Retrieves aggregated event data from Redis
  - Creates consolidated webhook event with all event IDs
  - **Sends via WebhookSubscriber** (not directly via HTTP client)
  - Cleans up processed events from Redis

### 5. WebhookSubscriber (SIMPLIFIED)
- **Location**: `com.comet.opik.api.resources.v1.events.WebhookSubscriber`
- **Purpose**: **ONLY sends webhooks via HTTP** - no aggregation logic
- **Responsibilities**:
  - Single public method: `sendWebhook(WebhookEvent event)` 
  - Validates webhook URLs and event data
  - Sends webhooks via WebhookHttpClient
  - **No knowledge of debouncing or aggregation**
  - **No Redis subscription** - just a service that sends HTTP requests

### 6. WebhookConfig.DebouncingConfig
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
┌─────────────────────────────────┐
│  WebhookEventStorageService     │
│  (Entry Point)                  │
│  - Check if debouncing enabled  │
└────────┬─────────────┬──────────┘
         │             │
 Debouncing ON   Debouncing OFF
         │             │
         │             └──────────────────────┐
         ▼                                    │
┌──────────────────────────────────────┐     │
│  WebhookEventAggregationService      │     │
│  Store event with timestamp in Redis │     │
│  Key: webhook_pending:{alertId}:     │     │
│       {eventType}                    │     │
│  Data:                               │     │
│    - event_timestamps: first_seen    │     │
│    - eventIds: [id1, id2, ...]       │     │
└──────────────────────────────────────┘     │
         │                                    │
         │  (Background Job - Every 5 seconds)│
         ▼                                    │
┌──────────────────────────────────────┐     │
│  WebhookBucketProcessorJob           │     │
│  Check for pending events            │     │
│  If (now - first_seen) >= window:   │     │
└────────┬─────────────────────────────┘     │
         │ YES: Ready to publish              │
         ▼                                    │
┌──────────────────────────────────────┐     │
│  WebhookBucketProcessor              │     │
│  - Create consolidated event         │     │
│  - Call WebhookSubscriber            │     │
│  - Delete pending key from Redis     │     │
└────────┬─────────────────────────────┘     │
         │                                    │
         └──────────────┬─────────────────────┘
                        ▼
         ┌──────────────────────────┐
         │   WebhookSubscriber      │
         │   sendWebhook(event)     │
         │   - Validate             │
         │   - Send via HTTP        │
         └──────────────────────────┘
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

1. **Event Arrives**: 
   - Application calls `WebhookEventStorageService.storeEvent(event)`
   - If debouncing enabled:
     - Check if pending key exists in Redis
     - If **first event**: Store current timestamp as `event_timestamps`
     - If **subsequent event**: Keep original timestamp, add event ID to list
   - If debouncing disabled:
     - Immediately call `WebhookSubscriber.sendWebhook(event)`

2. **Background Check** (Every 5 seconds):
   - `WebhookBucketProcessorJob` runs
   - Scan all keys matching `webhook_pending:*`
   - For each key:
     - Get `event_timestamps` value
     - Calculate: `elapsed = now - event_timestamps`
     - If `elapsed >= windowSize`: Mark as ready to publish

3. **Publishing**:
   - `WebhookBucketProcessor` creates consolidated webhook event with:
     - All aggregated event IDs
     - Event count
     - Aggregation metadata
   - Calls `WebhookSubscriber.sendWebhook(consolidatedEvent)`
   - `WebhookSubscriber` validates and sends via HTTP
   - Delete pending key from Redis

### Separation of Concerns

| Component | Responsible For | NOT Responsible For |
|-----------|----------------|---------------------|
| **WebhookEventStorageService** | Routing events based on config | Sending webhooks, Redis storage |
| **WebhookEventAggregationService** | Redis storage and grouping | Sending webhooks, scheduling |
| **WebhookBucketProcessorJob** | Scheduling and triggering processing | Creating events, sending webhooks |
| **WebhookBucketProcessor** | Creating consolidated events | Sending HTTP requests |
| **WebhookSubscriber** | **ONLY sending HTTP webhooks** | Aggregation, debouncing, Redis, scheduling |

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
6. **Clean Architecture**: Clear separation between aggregation and sending

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

### Scenario 3: Debouncing Disabled
```
Time 0s:  Event A arrives → Immediately sent via WebhookSubscriber
Time 10s: Event B arrives → Immediately sent via WebhookSubscriber
(No aggregation, each event sent individually)
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
- WebhookSubscriber HTTP sending

## Known Limitations & Future Enhancements

1. **Alert Configuration**: Currently uses placeholder values for:
   - Workspace ID
   - Webhook URL
   - Custom headers
   - **TODO**: Integrate with alert management service

2. **Monitoring**: Add metrics for:
   - Events aggregated count
   - Publish latency
   - Failed publications
   - WebhookSubscriber success/failure rates

3. **Rate Limiting**: Consider adding:
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
| 2.0     | 2025-01-30 | System | **Architecture redesign**: WebhookSubscriber only sends HTTP requests, added WebhookEventStorageService |