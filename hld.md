# Problem statement and requirements

Add the ability for non-technical users to review traces/threads. More in PRD - [https://www.notion.so/cometml/Improved-Human-Annotation-Flows-24c7124010a380638fe4f9c0d58c620d#24c7124010a380d58c53da7a2c7b7c79](https://www.notion.so/Improved-Human-Annotation-Flows-24c7124010a380638fe4f9c0d58c620d?pvs=21)

Epik - https://comet-ml.atlassian.net/browse/OPIK-2288

# MVP Goals

Satisfy requirements for v0.1 - [https://www.notion.so/cometml/Improved-Human-Annotation-Flows-24c7124010a380638fe4f9c0d58c620d?source=copy_link#2547124010a3803c9660d2e5ad5df075](https://www.notion.so/Improved-Human-Annotation-Flows-24c7124010a380638fe4f9c0d58c620d?pvs=21)

**NOTE:** The invocation flow will look different.

1. Admin user uses the "Invite a teammate" functionality, after which the SME user will have access to all Opik features as an admin.
2. Admin user shares a link to the target queue with the SME separately.

This flow is changed because the SME user needs access to the workspace to use existing endpoints. 

# MVP Non-goals

- Not adding new user roles to products
- No need for support for the self-hosted version, because we don’t have users concept there


# High-level design

The feature will be controlled by the feature fag `annotation_queues`

## UI Design

UI design described here with wireframes - https://app.excalidraw.com/l/5xGPZ1ID0vQ/7GuSFPxmzzq

## API specification

### AnnotationQueue

The next resource should be added AnnotationQueue

```tsx
export interface AggregatedFeedbackScore {
  name: string;
  value: number;
}

enum ANNOTATION_QUEUE_SCOPE {
	TRACE = "trace",
	THREAD = "thread"
}

export interface AnnotationQueueReviewer {
  username: string;
  status: number; // number of reviwed items
}

export interface AnnotationQueue {
  id: string;
  project_id: string;
  project_name: string; // should be returned for FE
  name: string;
  description: string;
  instructions: string;
  comments_enabled: boolean;
  feedback_definitions: string[];
  scope: ANNOTATION_QUEUE_SCOPE;
  reviewers?: AnnotationQueueReviewer[]; // virtual field, calculated base on feedback scores added to related traces/threads
	feedback_scores?: AggregatedFeedbackScore[]; // virtual field, calculated base on feedback scores added to related traces/threads
	items_count: number; // number of related items to queue
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
  last_scorred_at: string;
}

```

**List endpoint**

GET `/v1/private/annotation-queues/`
The endpoint has to support:

- filtering (Scope should be defined)
- sorting
- search by name

Response example:

```tsx
{
  "content": [
    {
      "id": "ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d",
      "project_id": "11111c4e-8b5f-7239-a123-456789abcdef",
			"project_name": "Example project",
      "name": "Product Feedback Analysis Queue",
      "description": "Queue for analyzing customer feedback traces related to product satisfaction and feature requests",
      "scope": "trace",
      "instructions": "Please review each trace for sentiment, categorize the feedback type (bug report, feature request, compliment), and rate the urgency level. Focus on identifying actionable insights that can improve our product.",
      "comments_enabled": true,
      "feedback_definitions": [
	      "01932c4e-8b5f-7239-a123-456789abcdef",
	    ],
      "reviewers": [
        {
          "username": "sarah.johnson",
          "status": 23
        }
      ],
      "feedback_scores": [
        {
          "name": "sentiment_analysis",
          "value": 0.72
        },
      ],
      "items_count": 156,
      "created_at": "2024-01-15T09:30:45.123Z",
      "created_by": "admin@company.com",
      "last_updated_at": "2024-01-18T14:22:17.456Z",
      "last_updated_by": "sarah.johnson",
      "last_scorred_at": "2024-01-18T16:45:32.789Z"
    }
  ],
  "sortable_by": [
    "id",
    "name",
    "description",
    "instructions",
    "scope", 
    "items_count",
    "created_at",
    "created_by",
    "last_updated_at",
    "last_updated_by",
    "last_scorred_at"
  ],
  "total": 1
}
```

**Get by ID endpoint**

GET `/v1/private/annotation-queues/{id}`

Create endpoint 

POST `/v1/private/annotation-queues/`

Payload: 

```tsx
{
	"project_id": "11111c4e-8b5f-7239-a123-456789abcdef",
	"name": "Product Feedback Analysis Queue",
  "description": "Queue for analyzing customer feedback traces related to product satisfaction and feature requests",
	"instructions": "Please review each trace for sentiment, categorize the feedback type (bug report, feature request, compliment), and rate the urgency level. Focus on identifying actionable insights that can improve our product.",
  "scope": "trace",
  "comments_enabled": true,
  "feedback_definitions": [
    "01932c4e-8b5f-7239-a123-456789abcdef",
  ],
}
```

Update endpoint

The `scope` field cannot be updated

PATCH `/v1/private/annotation-queues/{id}`

Payload: 

```tsx
{
	"name": "Product Feedback Analysis Queue",
  "description": "Queue for analyzing customer feedback traces related to product satisfaction and feature requests",
	"instructions": "product.",
  "comments_enabled": true,
  "feedback_definitions": [
    "01932c4e-8b5f-7239-a123-456789abcdef",
    "22932c4e-8b5f-7239-a433-456789abcdef",
  ],
}
```

Delete endpoint

POST `/v1/private/annotation-queues/delete`

Payload: 

```tsx
{
	"ids": ["ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d"]
}
```

### AnnotationQueue items

Add items to the queue endpoint 

POST `/v1/private/annotation-queues/{id}/items/add`

Payload: 

```tsx
{
	"ids": ["ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d"]
}
```

Delete items from the queue endpoint 

POST `/v1/private/annotation-queues/{id}/items/delete` 

Payload: 

```tsx
{
	"ids": ["ann_queue_7f3e4d2a-8b1c-4f6e-9a2d-5c8b7e4f1a3d"]
}
```

### Traces and Threads

Modify the following existing traces endpoints to add support for the new AnnotationQueue filter.

GET `/v1/private/traces/` 

GET `/v1/private/traces/stats` 

GET `/v1/private/traces/threads`

```tsx
[{
    "id": "mf3wvm1w",
    "field": "annotation_queue_id",
    "type": "string",
    "operator": "=",
    "key": "",
    "value": "01987960-b464-74f3-b176-bb2de7391867"
}]
```

## Frontend task

1. [FE] Add support of description field for feedback-definition resource ~ 1SP
2. [FE] Implement create/edit AnnotationQueue modal ~ 4SP
    1. Create all API related hooks for AnnotationQueue (1)
    2. Implement select scores component(1)
    3. Implement all other fields and validation(2)
3. [FE] Implement Add to AnnotationQueue modal ~3SP
    1. Implement all API related hooks to add/delete items to AnnotationQueue (1)
    2. Implement modal and success popover (1)
    3. Integrate this modal in 4 places (traces/threads) (1)
4. [FE] Implement AnnotationQueues page ~ 2SP
    1. Implement all functionality and connect all parts (2)
5. [FE] Implement AnnotationQueues tab  ~ 1SP
    1. Integrate all functionality from the main page (1)
6. [FE] Implement AnnotationQueues details page ~ 7SP
    1. Implement config tab (1.5)
    2. Implement items tab as threads (1.5)
    3. Implement items tab as traces (1.5)
    4. Implement a page with instructions on  how to add (1)
    5. implement empty state (0.5)
    6. integrate details page, AnnotationQueue Tab, and AnnotationQueue Page (1)
7. [FE] Implement SME AnnotationQueue flow pages ~ 11SP
    1. Implement Instruction page(1)
    2. Implement page skeleton with progress bar, and navigation logic (2.5)
    3. Implement comments and feedback score section (trace and threads support) (3)
    4. Implement preview data section (1)
    5. Implement complete page (0.5)
    6. Implement list view page (traces and threads) (3)

Total: 29 SP

## Backend tasks:

1. [BE] Add a feature flag to control the feature - 1SP
2. [BE] Add description field for feedback-definition resource ~ 1SP 
3. [BE] Implement create new AnnotationQueue endpoint ~ 4 SP
    1. generate new tables in ClickHouse
    2. Implement a POST endpoint to create an AnnotationQueue
4. [BE] Implement remove AnnotationQueue endpoint ~ 3 SP
5. [BE] Implement list and get endpoints for AnnotationQueue ~ 7SP
6. [BE] Implement endpoints to add/remove AnnotationQueue items ~4 SP
7. [BE] Update trace/threads related endpoint to support filtering by AnnotationQueueID ~ 4-6 SP
    1. update threads list endpoint
    2. update traces list endpoint
    3. update traces stats endpoint

Total: 24 -26 Story Points

## Feature toggles

`annotation_queues` - control visibility of next components.
if enabled: 

- The menu link to the AnnotationQueue page becomes visible
- The AnnotationQueue tab in the project page becomes visible
- Buttons to add a thread[s] to the AnnotationQueue are visible
- Buttons to add a trace[s] to the AnnotationQueue are visible

## Deployment and releases

## Testing strategy

Manual testing

## Documentation