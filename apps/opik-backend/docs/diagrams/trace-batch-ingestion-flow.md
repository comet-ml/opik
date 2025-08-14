# Trace Batch Ingestion Flow

This diagram illustrates the complete flow of trace batch ingestion in the Opik Java backend service, from client request to event processing.

## Architecture Overview

The trace batch ingestion system follows a reactive, event-driven architecture using Project Reactor and ClickHouse for high-performance data processing.

```mermaid
flowchart TD
    A[Client Request] --> B[TracesResource.createTraces]
    B --> C["TraceService.create(TraceBatch)"]
    
    C --> D{Validate Batch}
    D -->|Empty| E[Throw Exception]
    D -->|Valid| F[Deduplicate Traces]
    
    F --> G[Extract Project Names]
    G --> H[Resolve Projects]
    
    H --> I["ProjectService.getOrCreate"]
    I --> J[Bind Traces to Projects]
    
    J --> K["TransactionTemplateAsync.nonTransaction"]
    K --> L["TraceDAO.batchInsert"]
    
    L --> M[Build Batch Insert SQL]
    M --> N[Execute ClickHouse Query]
    
    N --> O[Database Insert Success]
    O --> P[Post TracesCreated Event]
    
    P --> Q["EventBus.post"]
    
    Q --> R["TraceThreadListener.onTracesCreated"]
    Q --> S["OnlineScoringSampler.onTracesCreated"]
    Q --> T["ProjectEventListener.onTracesCreated"]
    Q --> U["BiEventListener.onTracesCreated"]
    
    R --> V[Process Trace Threads]
    V --> W["TraceThreadService.processTraceThreads"]
    W --> X[Update Thread Status]
    
    S --> Y[Sample for Online Scoring]
    Y --> Z[Enqueue to Redis Stream]
    
    T --> AA[Update Project Last Updated]
    AA --> BB["ProjectService.recordLastUpdatedTrace"]
    
    U --> CC[Check First Trace Report]
    CC --> DD[Send BI Event]
    
    subgraph "Database Layer"
        N
        O
    end
    
    subgraph "Event Processing"
        R
        S
        T
        U
    end
    
    subgraph "Async Processing"
        V
        Y
        AA
        CC
    end
    
    subgraph "Error Handling"
        E
        EE[Retry Logic]
        FF[Error Logging]
    end
    
    style A fill:#e1f5fe
    style O fill:#c8e6c9
    style Q fill:#fff3e0
    style E fill:#ffcdd2
```

## Component Details

The following component diagram illustrates the architectural layers and their relationships in the trace batch ingestion system:

```mermaid
graph TB
    subgraph "Client Layer"
        Client[Client Application]
    end

    subgraph "API Layer"
        TracesResource[TracesResource<br/>REST Endpoint]
        RateLimiter[Rate Limiter<br/>Workspace/User Limits]
        Validator[Validator<br/>Batch Size & Data]
    end

    subgraph "Service Layer"
        TraceService[TraceService<br/>Main Orchestration]
        ProjectService[ProjectService<br/>Project Management]
        Deduplicator[Deduplicator<br/>Remove Duplicates]
        DataBinder[Data Binder<br/>Bind to Projects]
    end

    subgraph "Data Access Layer"
        TraceDAO[TraceDAO<br/>Database Access]
        TransactionTemplate[TransactionTemplateAsync<br/>Non-blocking Operations]
    end

    subgraph "Database Layer"
        ClickHouse[(ClickHouse<br/>Time-series Database)]
    end

    subgraph "Event System"
        EventBus[EventBus<br/>Google EventBus]
        TracesCreatedEvent[TracesCreated Event]
    end

    subgraph "Event Handlers"
        TraceThreadListener[TraceThreadListener<br/>Thread Management]
        OnlineScoringSampler[OnlineScoringSampler<br/>Automated Scoring]
        ProjectEventListener[ProjectEventListener<br/>Project Metadata]
        BiEventListener[BiEventListener<br/>Business Intelligence]
    end

    subgraph "External Services"
        Redis[(Redis<br/>Stream Processing)]
        Analytics[Analytics Service<br/>Usage Reporting]
    end

    %% Client Layer Connections
    Client --> TracesResource

    %% API Layer Connections
    TracesResource --> RateLimiter
    TracesResource --> Validator
    TracesResource --> TraceService

    %% Service Layer Connections
    TraceService --> Deduplicator
    TraceService --> ProjectService
    TraceService --> DataBinder
    TraceService --> TraceDAO

    %% Data Access Layer Connections
    TraceDAO --> TransactionTemplate
    TransactionTemplate --> ClickHouse

    %% Event System Connections
    TraceService --> EventBus
    EventBus --> TracesCreatedEvent
    TracesCreatedEvent --> TraceThreadListener
    TracesCreatedEvent --> OnlineScoringSampler
    TracesCreatedEvent --> ProjectEventListener
    TracesCreatedEvent --> BiEventListener

    %% Event Handler Connections
    OnlineScoringSampler --> Redis
    BiEventListener --> Analytics

    %% Styling
    classDef apiLayer fill:#e3f2fd
    classDef serviceLayer fill:#f3e5f5
    classDef dataLayer fill:#e8f5e8
    classDef eventLayer fill:#fff3e0
    classDef externalLayer fill:#ffebee

    class TracesResource,RateLimiter,Validator apiLayer
    class TraceService,ProjectService,Deduplicator,DataBinder serviceLayer
    class TraceDAO,TransactionTemplate,ClickHouse dataLayer
    class EventBus,TracesCreatedEvent,TraceThreadListener,OnlineScoringSampler,ProjectEventListener,BiEventListener eventLayer
    class Redis,Analytics externalLayer
```

### **Architectural Layers:**

#### **1. Request Handling Layer**
- **TracesResource.createTraces()**: REST endpoint for batch trace creation
- **Validation**: Batch size (1-1000 traces), trace data validation
- **Rate Limiting**: Applied at resource level with workspace and user limits

#### **2. Service Layer**
- **TraceService.create(TraceBatch)**: Main orchestration service
- **Deduplication**: Removes duplicate traces based on ID and lastUpdatedAt
- **Project Resolution**: Groups traces by project and ensures projects exist
- **Data Binding**: Associates traces with their respective project IDs

#### **3. Database Operations**
- **TransactionTemplateAsync.nonTransaction()**: Non-blocking database operations
- **TraceDAO.batchInsert()**: Optimized batch insert for ClickHouse
- **ClickHouse**: Time-series database optimized for high-volume data

#### **4. Event-Driven Architecture**
- **TracesCreated Event**: Posted after successful database insertion
- **EventBus**: Google EventBus for event distribution
- **Multiple Listeners**: Process different aspects of trace creation

#### **5. Event Handlers**

##### **TraceThreadListener**
- Manages conversation threads and thread status
- Groups traces by project and thread ID
- Updates thread metadata and status

##### **OnlineScoringSampler**
- Samples traces for automated scoring
- Enqueues samples to Redis Stream for processing
- Supports automation rule evaluation

##### **ProjectEventListener**
- Updates project metadata
- Records last updated trace timestamps
- Maintains project statistics

##### **BiEventListener**
- Handles business intelligence reporting
- Tracks first trace creation events
- Reports usage analytics

## Key Features

### **Performance Optimizations**
- **Batch Processing**: Single SQL statement for multiple traces
- **Non-blocking I/O**: Reactive programming with Project Reactor
- **Deduplication**: Prevents duplicate data insertion
- **Connection Pooling**: Efficient database connection management

### **Error Handling**
- **Retry Logic**: Automatic retry for transient failures
- **Error Logging**: Comprehensive error tracking
- **Graceful Degradation**: Continues processing on partial failures

### **Observability**
- **OpenTelemetry Spans**: Distributed tracing throughout the flow
- **Structured Logging**: Consistent log format with context
- **Metrics**: Performance and error rate monitoring

## Data Flow

The following sequence diagram illustrates the step-by-step flow of trace batch ingestion:

```mermaid
sequenceDiagram
    participant Client
    participant TracesResource
    participant TraceService
    participant ProjectService
    participant TraceDAO
    participant ClickHouse
    participant EventBus
    participant TraceThreadListener
    participant OnlineScoringSampler
    participant ProjectEventListener
    participant BiEventListener
    participant Redis

    Note over Client, Redis: Trace Batch Ingestion Flow

    Client->>TracesResource: POST /api/v1/priv/traces/batch
    Note right of Client: 1-1000 traces

    TracesResource->>TraceService: create(TraceBatch)
    
    TraceService->>TraceService: Validate batch size
    Note right of TraceService: 1-1000 traces allowed
    
    TraceService->>TraceService: Deduplicate traces
    Note right of TraceService: Remove duplicates by ID & lastUpdatedAt
    
    TraceService->>TraceService: Extract project names
    Note right of TraceService: Group by project
    
    TraceService->>ProjectService: getOrCreate(projectNames)
    ProjectService-->>TraceService: Project entities
    
    TraceService->>TraceService: Bind traces to projects
    Note right of TraceService: Associate with project IDs
    
    TraceService->>TraceDAO: batchInsert(traces)
    TraceDAO->>ClickHouse: Execute batch SQL
    ClickHouse-->>TraceDAO: Insert success
    TraceDAO-->>TraceService: Insert count
    
    TraceService->>EventBus: post(TracesCreated)
    Note right of EventBus: Event with traces, workspace, user
    
    par Asynchronous Event Processing
        EventBus->>TraceThreadListener: onTracesCreated()
        TraceThreadListener->>TraceThreadListener: Process thread groups
        Note right of TraceThreadListener: Update thread status & metadata
    and
        EventBus->>OnlineScoringSampler: onTracesCreated()
        OnlineScoringSampler->>OnlineScoringSampler: Sample traces
        OnlineScoringSampler->>Redis: Enqueue for scoring
        Note right of Redis: Automation rule evaluation
    and
        EventBus->>ProjectEventListener: onTracesCreated()
        ProjectEventListener->>ProjectEventListener: Update project metadata
        Note right of ProjectEventListener: Record last updated timestamps
    and
        EventBus->>BiEventListener: onTracesCreated()
        BiEventListener->>BiEventListener: Check first trace report
        Note right of BiEventListener: Send analytics events
    end
    
    TraceService-->>TracesResource: Success response
    TracesResource-->>Client: 204 No Content

    Note over Client, Redis: Flow Complete
```

### **Flow Steps Explained:**

1. **Client Request**: Client sends batch request with 1-1000 traces
2. **Validation**: Service validates batch size and trace data
3. **Deduplication**: Removes duplicate traces based on ID and lastUpdatedAt
4. **Project Resolution**: Groups traces by project and ensures projects exist
5. **Data Binding**: Associates traces with their respective project IDs
6. **Database Insert**: Executes optimized batch insert in ClickHouse
7. **Event Publishing**: Posts TracesCreated event to event bus
8. **Async Processing**: Multiple listeners process events concurrently:
   - **Thread Management**: Updates conversation threads and status
   - **Online Scoring**: Samples traces for automated evaluation
   - **Project Updates**: Maintains project metadata and statistics
   - **Analytics**: Tracks usage and business intelligence data

## Technology Stack

- **Framework**: Dropwizard with JAX-RS
- **Reactive**: Project Reactor (Mono/Flux)
- **Database**: ClickHouse for time-series data
- **Event Bus**: Google EventBus
- **Caching**: Redis for stream processing
- **Observability**: OpenTelemetry
- **Validation**: Jakarta Validation

## Related Files

- `TracesResource.java`: REST endpoint implementation
- `TraceService.java`: Main service orchestration
- `TraceDAO.java`: Database access layer
- `TraceThreadListener.java`: Thread management
- `OnlineScoringSampler.java`: Scoring automation
- `ProjectEventListener.java`: Project metadata updates
- `BiEventListener.java`: Business intelligence
