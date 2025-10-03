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
│     • Retrieve bucket data (event IDs, workspace ID)                  │
│     • Fetch alert configuration from AlertService                     │
│     • Create payload with alert and event data                        │
│     • Publish webhook via WebhookPublisher                            │
│     • Delete bucket                                                     │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
                                  ↓
┌────────────────────────────────────────────────────────────────────────┐
│                        WebhookPublisher                                 │
│              (Publishes webhooks to message queue)                     │
│                                                                         │
│  • publishWebhookEvent() - Publishes event to webhook queue           │
└────────────────────────────────────────────────────────────────────────┘
```

### Separation of Concerns

| Component | Responsibility |
|-----------|---------------|
| **Alert Evaluation** | Checks if event matches alert config, adds to bucket via AlertBucketService |
| **AlertBucketService** | Stores and retrieves alert event buckets in Redis with workspace context |
| **AlertJob** | Scheduled job that checks buckets, retrieves alert config from Alert Service, creates payload, publishes webhooks |
| **WebhookPublisher** | **ONLY publishes webhook events** - no knowledge of aggregation |

## Data Flow

### 1. Event Occurs

```java
// Alert evaluation logic (to be implemented)
if (eventMatchesAlertConfig(event, alert)) {
    alertBucketService.addEventToBucket(
        alert.getId(),
        workspaceId,
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
  "firstSeen": "1704067200000",
  "windowSize": "60000",
  "workspaceId": "workspace-123"
}
```

**Note:** The bucket stores:
- `windowSize`: The debouncing window (in milliseconds) active when the bucket was created
- `workspaceId`: The workspace ID for retrieving alert configuration without RequestContext
These fields ensure that configuration changes do not affect existing buckets and allow background jobs to access alert data.

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
    String workspaceId,
    AlertEventType eventType,
    String eventId) {
    
    String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
    RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);
    long currentWindowSizeMillis = webhookConfig.getDebouncing().getWindowSize().toMilliseconds();
    
    // Add event ID to set
    // If first event in bucket:
    //   - Store firstSeen timestamp
    //   - Store windowSize (preserves config at creation time)
    //   - Store workspaceId (enables background job to retrieve alert)
    //   - Set bucket TTL (ONLY on first event, NOT refreshed on subsequent events)
    // Subsequent events:
    //   - DO NOT update windowSize or workspaceId (preserves original config and context)
    //   - DO NOT refresh TTL (bucket expires based on original TTL)
}

public Flux<String> getBucketsReadyToProcess() {
    // Scan all alert_bucket:* keys
    // For each bucket:
    //   - Retrieve firstSeen timestamp AND windowSize from bucket
    //   - If (now - firstSeen) >= stored windowSize
    //   - Return bucket key
    // Note: Uses the windowSize stored in the bucket, not current config
}

public Mono<BucketData> getBucketData(String bucketKey) {
    // Retrieves complete bucket data including:
    //   - eventIds: Set of aggregated event IDs
    //   - firstSeen: Timestamp when first event was added
    //   - windowSize: Debouncing window size (milliseconds)
    //   - workspaceId: Workspace ID for accessing alert configuration
}
```

### Timestamp-Based Debouncing

```
Event Timeline:

t0: First event arrives → Store firstSeen = t0, windowSize = 60000ms, Set TTL = 3 minutes
t1: Second event arrives → Keep firstSeen = t0, Keep windowSize = 60000ms, Keep TTL (not refreshed)
t2: Third event arrives → Keep firstSeen = t0, Keep windowSize = 60000ms, Keep TTL (not refreshed)
...
t60: AlertJob runs → (t60 - t0) >= 60s → Send consolidated webhook
t60+: Bucket deleted by AlertJob after processing
     (If not deleted by AlertJob, Redis automatically expires bucket after TTL)
```

**TTL Behavior:**
- TTL is set ONLY when the first event is added to a new bucket
- Subsequent events to the same bucket do NOT refresh the TTL
- This prevents buckets from living indefinitely if events keep arriving
- The `bucketTtl` configuration (default: 3 minutes) is the safety cleanup mechanism
- After processing, buckets are explicitly deleted by AlertJob

### Handling Configuration Changes

When the debouncing `windowSize` configuration is changed, the system handles existing and new buckets correctly:

**Scenario:**
1. Alert has debouncing window = 60 seconds
2. Bucket A is created at t0 with events
3. Configuration is changed to debouncing window = 120 seconds
4. New events for same alert arrive

**Behavior:**
- **Bucket A** (created with 60s window):
  - Continues to use its stored windowSize = 60000ms
  - Will be processed when (now - firstSeen) >= 60s
  - Not affected by configuration change
  
- **New Bucket B** (created after config change):
  - Stores new windowSize = 120000ms
  - Will be processed when (now - firstSeen) >= 120s
  - Uses the new configuration

**Key Implementation Details:**
1. `addEventToBucket()` stores `windowSize` only for the first event in a bucket
2. Subsequent events to the same bucket preserve the original `windowSize`
3. `getBucketsReadyToProcess()` reads the `windowSize` from each bucket (not from current config)
4. This ensures existing buckets are processed with their original configuration

**Test Coverage:**
See `AlertBucketServiceTest.java` for comprehensive tests covering:
- Window size storage with first event
- Window size preservation with subsequent events
- Multiple buckets with different window sizes being processed independently
- Configuration changes creating new buckets while existing ones continue with original settings

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
2. **Metrics**: Track aggregation stats, publish latency, success/failure rates
3. **Rate Limiting**: Implement per-alert webhook rate limits
4. **Batch Size Limits**: Configurable maximum events per consolidated webhook

## Key Files

### Core Implementation
- `AlertBucketService.java` - Redis bucket management
- `AlertJob.java` - Scheduled job for processing buckets
- `WebhookPublisher.java` - Webhook event publisher
- `WebhookConfig.java` - Debouncing configuration
- `AlertService.java` - Alert configuration service (includes `getByIdAndWorkspace`)

### Data Models
- `Alert.java` - Alert configuration
- `AlertTrigger.java` - Alert trigger configuration
- `WebhookEvent.java` - Webhook event structure
- `WebhookEventTypes.java` - Event type enum (includes `ALERT_FIRED`)

### Services
- `AlertService.java` - Alert CRUD operations
- `AlertDAO.java` - Alert database access

## Version History

### Version 1.2 (Current)
- **Workspace Context**: Added `workspaceId` storage in buckets for background job access
  - AlertJob can now retrieve alert configuration without RequestContext
  - Added `getByIdAndWorkspace()` method to AlertService
- **AlertJob Updates**: Uses `getBucketData()` to retrieve complete bucket information
  - Fetches workspace ID from bucket data
  - Retrieves alert configuration using workspace context
- **Documentation**: Updated to use `WebhookPublisher` instead of `WebhookSubscriber`

### Version 1.1
- **Configuration Change Handling**: Buckets now store their creation-time `windowSize`
  - Existing buckets continue to use their original debouncing window
  - New buckets created after configuration changes use the updated window
  - Added comprehensive test coverage in `AlertBucketServiceTest.java`
- Enhanced bucket data structure to include `windowSize` field
- Modified `getBucketsReadyToProcess()` to use stored window size instead of current config

### Version 1.0 (2024-01-01)
- Initial implementation
- Timestamp-based debouncing
- 5-second AlertJob schedule
- WebhookPublisher only publishes events to queue
- AlertBucketService manages Redis storage with workspace context
- AlertJob orchestrates bucket processing, fetches alert data, and publishes webhooks
- Alert data is retrieved using workspaceId from bucket (no RequestContext needed)