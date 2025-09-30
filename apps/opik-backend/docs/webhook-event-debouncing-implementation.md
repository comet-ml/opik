# Webhook Alert Debouncing Implementation

## Overview

This document describes the implementation of webhook alert debouncing in the Opik backend. The system aggregates multiple alert events within a configurable time window and sends consolidated webhook notifications.

## Architecture

### Core Components

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Alert Evaluation                               │
│  (Checks if events match alert config and adds to bucket)             │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
                                  ↓
┌────────────────────────────────────────────────────────────────────────┐
│                        AlertBucketService                               │
│              (Manages alert event buckets in Redis)                    │
│                                                                         │
│  • addEventToBucket()     - Adds event to Redis bucket                │
│  • getBucketsReadyToProcess() - Returns buckets past debounce window  │
│  • getBucketEventIds()    - Retrieves aggregated event IDs            │
│  • deleteBucket()         - Removes processed bucket                   │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
                                  ↓
┌────────────────────────────────────────────────────────────────────────┐
│                            AlertJob                                     │
│             (Scheduled job runs every 5 seconds)                       │
│                                                                         │
│  1. Check buckets ready to process                                     │
│  2. For each ready bucket:                                             │
│     • Retrieve alert configuration                                     │
│     • Get aggregated event IDs                                         │
│     • Create consolidated webhook event                                 │
│     • Send via WebhookSubscriber                                       │
│     • Delete bucket                                                     │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
                                  ↓
┌────────────────────────────────────────────────────────────────────────┐
│                        WebhookSubscriber                                │
│                 (Sends webhooks via HTTP)                              │
│                                                                         │
│  • sendWebhook() - Validates and sends HTTP request                   │
└────────────────────────────────────────────────────────────────────────┘
```

### Separation of Concerns

| Component | Responsibility |
|-----------|---------------|
| **Alert Evaluation** | Checks if event matches alert config, adds to bucket via AlertBucketService |
| **AlertBucketService** | Stores and retrieves alert event buckets in Redis |
| **AlertJob** | Scheduled job that checks buckets, retrieves alert config, creates consolidated events, triggers webhooks |
| **WebhookSubscriber** | **ONLY sends HTTP webhooks** - no knowledge of aggregation |

## Data Flow

### 1. Event Occurs

```java
// Alert evaluation logic (to be implemented)
if (eventMatchesAlertConfig(event, alert)) {
    alertBucketService.addEventToBucket(
        alert.getId(),
        eventType,
        event.getId()
    ).subscribe();
}
```

### 2. Event Storage in Redis

**Bucket Key Format:** `alert_bucket:{alertId}:{eventType}`

**Bucket Data Structure:**
```json
{
  "eventIds": "[\"event-1\", \"event-2\", \"event-3\"]",
  "firstSeen": "1704067200000"
}
```

### 3. Background Processing

**AlertJob** (runs every 5 seconds):

```java
@Every("5s")
public class AlertJob extends Job {
    @Override
    public void doJob(JobExecutionContext context) {
        // Check buckets ready to process
        bucketService.getBucketsReadyToProcess()
            .flatMap(this::processBucket)
            .blockLast();
    }
}
```

### 4. Webhook Notification

When a bucket is ready:

```java
private Mono<Void> processBucket(String bucketKey) {
    // 1. Parse alert ID and event type
    // 2. Retrieve alert configuration from AlertService
    // 3. Get aggregated event IDs from bucket
    // 4. Create consolidated webhook event
    // 5. Send via WebhookSubscriber
    // 6. Delete bucket
}
```

## Configuration

### Debouncing Settings

```yaml
webhook:
  debouncing:
    enabled: true
    windowSize: 60 seconds    # Time to wait before sending consolidated notification
    bucketTtl: 3 minutes      # Bucket expiration time (safety cleanup)
```

### Alert Configuration

```java
Alert {
    id: UUID
    name: String
    enabled: Boolean
    webhook: {
        url: String
        headers: Map<String, String>
        secretToken: String
    }
    triggers: List<AlertTrigger> {
        eventType: AlertEventType
        triggerConfigs: List<TriggerConfig>
    }
}
```

## Implementation Details

### Redis Bucket Management

**AlertBucketService:**

```java
public Mono<Void> addEventToBucket(
    UUID alertId,
    AlertEventType eventType,
    String eventId) {
    
    String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
    RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);
    
    // Add event ID to set
    // Store firstSeen timestamp if first event
    // Set bucket TTL
}

public Flux<String> getBucketsReadyToProcess() {
    // Scan all alert_bucket:* keys
    // For each bucket:
    //   - Check firstSeen timestamp
    //   - If (now - firstSeen) >= debouncingWindow
    //   - Return bucket key
}
```

### Timestamp-Based Debouncing

```
Event Timeline:

t0: First event arrives → Store firstSeen = t0
t1: Second event arrives → Keep firstSeen = t0
t2: Third event arrives → Keep firstSeen = t0
...
t60: AlertJob runs → (t60 - t0) >= 60s → Send consolidated webhook
```

### Consolidated Webhook Payload

```json
{
  "id": "alert-{alertId}-{uuid}",
  "eventType": "alert.fired",
  "alertId": "{alertId}",
  "alertName": "High Error Rate",
  "workspaceId": "{workspaceId}",
  "userName": "system",
  "url": "{webhookUrl}",
  "payload": {
    "alertId": "{alertId}",
    "alertName": "High Error Rate",
    "eventType": "trace:errors",
    "eventIds": ["event-1", "event-2", "event-3"],
    "eventCount": 3,
    "aggregationType": "consolidated",
    "message": "Alert 'High Error Rate': 3 trace:errors events aggregated"
  },
  "headers": {
    "X-Custom-Header": "value"
  }
}
```

## Job Management

### AlertJob Schedule

- **Frequency**: Every 5 seconds
- **Concurrency**: Disabled (`@DisallowConcurrentExecution`)
- **Purpose**: Check buckets and trigger webhooks for events past debouncing window

### Job Execution Flow

```java
1. getBucketsReadyToProcess() → Flux<String> of bucket keys
2. For each bucketKey:
   a. Parse alertId and eventType
   b. Retrieve Alert configuration
   c. Get aggregated event IDs
   d. Create consolidated webhook event
   e. Send via WebhookSubscriber
   f. Delete bucket
3. Error handling: Continue on error, log failures
```

## Error Handling

### Bucket Processing Errors

```java
bucketService.getBucketsReadyToProcess()
    .flatMap(this::processBucket)
    .onErrorContinue((throwable, bucketKey) -> {
        log.error("Failed to process bucket '{}': {}",
            bucketKey, throwable.getMessage(), throwable);
    })
    .blockLast();
```

### Alert Configuration Errors

```java
// If alert is disabled
if (!alert.enabled()) {
    log.warn("Alert '{}' is disabled, skipping webhook", alert.id());
    return Mono.empty();
}

// If webhook configuration is missing
if (alert.webhook() == null || alert.webhook().url() == null) {
    log.error("Alert '{}' has no webhook configuration, skipping", alert.id());
    return Mono.empty();
}
```

### Webhook Sending Errors

```java
return webhookSubscriber.sendWebhook(webhookEvent)
    .doOnSuccess(__ -> log.info("Successfully sent webhook for alert '{}'", alert.id()))
    .doOnError(error -> log.error("Failed to send webhook for alert '{}': {}",
        alert.id(), error.getMessage(), error));
```

## Testing

### Unit Tests

```java
@Test
void shouldAddEventToBucket() {
    // Test event addition to Redis bucket
}

@Test
void shouldReturnBucketsReadyToProcess() {
    // Test bucket readiness based on firstSeen timestamp
}

@Test
void shouldProcessBucketAndSendWebhook() {
    // Test AlertJob processing and webhook sending
}

@Test
void shouldHandleDisabledAlert() {
    // Test skipping webhooks for disabled alerts
}
```

### Integration Tests

- Test complete flow from event to webhook
- Test bucket TTL and cleanup
- Test concurrent event additions to same bucket
- Test AlertJob execution with multiple ready buckets

## Future Enhancements

1. **Alert Configuration Management**: Integration with Alert CRUD operations
2. **Workspace Context**: Proper workspace ID handling in webhook events
3. **Metrics**: Track aggregation stats, publish latency, success/failure rates
4. **Rate Limiting**: Implement per-alert webhook rate limits
5. **Batch Size Limits**: Configurable maximum events per consolidated webhook

## Key Files

### Core Implementation
- `AlertBucketService.java` - Redis bucket management
- `AlertJob.java` - Scheduled job for processing buckets
- `WebhookSubscriber.java` - HTTP webhook sender
- `WebhookConfig.java` - Debouncing configuration

### Data Models
- `Alert.java` - Alert configuration
- `AlertTrigger.java` - Alert trigger configuration
- `WebhookEvent.java` - Webhook event structure
- `WebhookEventTypes.java` - Event type enum (includes `ALERT_FIRED`)

### Services
- `AlertService.java` - Alert CRUD operations
- `AlertDAO.java` - Alert database access

## Version History

### Version 1.0 (2024-01-01)
- Initial implementation
- Timestamp-based debouncing
- 5-second AlertJob schedule
- WebhookSubscriber only sends HTTP
- AlertBucketService manages Redis storage
- AlertJob orchestrates bucket processing and webhook triggering