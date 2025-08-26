# Human Feedback System Documentation

## Overview

The Human Feedback System enables Subject Matter Experts (SMEs) to review and annotate AI agent outputs through structured annotation queues. This system provides a comprehensive workflow for collecting human feedback on AI-generated content to improve model performance and ensure quality control.

## Architecture

### System Components

1. **Annotation Queues**: Containers for organizing items that need human review
2. **Queue Items**: Individual traces or threads awaiting annotation
3. **Annotations**: SME feedback including ratings, comments, and structured metrics
4. **SME Access Management**: Public interface for SME annotation workflow

### Technology Stack

**Backend:**
- Java 21 with Dropwizard framework
- MySQL database with Liquibase migrations
- JDBI3 for database access
- RESTful API design

**Frontend:**
- React 18 with TypeScript
- TanStack Router for navigation
- TanStack Query for state management
- Tailwind CSS for styling
- React Hook Form with Zod validation

## Database Schema

### Tables

#### `annotation_queues`
```sql
CREATE TABLE annotation_queues (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'completed', 'paused')),
  created_by VARCHAR(36) NOT NULL,
  project_id VARCHAR(36) NOT NULL,
  template_id VARCHAR(36),
  visible_fields JSON DEFAULT '["input", "output", "timestamp"]',
  required_metrics JSON DEFAULT '["rating"]',
  optional_metrics JSON DEFAULT '["comment"]',
  instructions TEXT,
  due_date TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  
  FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
  INDEX idx_annotation_queues_project_id (project_id),
  INDEX idx_annotation_queues_created_by (created_by),
  INDEX idx_annotation_queues_status (status)
);
```

#### `queue_items`
```sql
CREATE TABLE queue_items (
  id VARCHAR(36) PRIMARY KEY,
  queue_id VARCHAR(36) NOT NULL,
  item_type VARCHAR(10) NOT NULL CHECK (item_type IN ('trace', 'thread')),
  item_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'in_progress', 'completed', 'skipped')),
  assigned_sme VARCHAR(36),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP,
  
  FOREIGN KEY (queue_id) REFERENCES annotation_queues(id) ON DELETE CASCADE,
  UNIQUE KEY unique_queue_item (queue_id, item_type, item_id),
  INDEX idx_queue_items_queue_id_status (queue_id, status),
  INDEX idx_queue_items_assigned_sme (assigned_sme),
  INDEX idx_queue_items_item_type_item_id (item_type, item_id)
);
```

#### `annotations`
```sql
CREATE TABLE annotations (
  id VARCHAR(36) PRIMARY KEY,
  queue_item_id VARCHAR(36) NOT NULL,
  sme_id VARCHAR(36) NOT NULL,
  metrics JSON NOT NULL DEFAULT '{}',
  comment TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  
  FOREIGN KEY (queue_item_id) REFERENCES queue_items(id) ON DELETE CASCADE,
  UNIQUE KEY unique_annotation_per_sme (queue_item_id, sme_id),
  INDEX idx_annotations_queue_item_id (queue_item_id),
  INDEX idx_annotations_sme_id (sme_id)
);
```

## API Endpoints

### Admin API (Private)

#### Annotation Queues Management
- `GET /v1/private/annotation-queues` - List annotation queues
- `POST /v1/private/annotation-queues` - Create annotation queue
- `GET /v1/private/annotation-queues/{id}` - Get annotation queue details
- `PUT /v1/private/annotation-queues/{id}` - Update annotation queue
- `DELETE /v1/private/annotation-queues/{id}` - Delete annotation queue

#### Queue Items Management
- `GET /v1/private/queue-items` - List queue items
- `POST /v1/private/queue-items` - Add items to queue
- `DELETE /v1/private/queue-items` - Remove item from queue

### SME API (Public)

#### Annotation Interface
- `GET /v1/public/annotation-queues/{queueId}` - Get queue for SME access
- `GET /v1/public/annotation-queues/{queueId}/next-item` - Get next item for annotation
- `POST /v1/public/annotation-queues/{queueId}/items/{itemId}/annotations` - Submit annotation
- `PUT /v1/public/annotation-queues/{queueId}/items/{itemId}/status` - Update item status

## Frontend Components

### Admin Interface

#### AnnotationQueuesPage
- **Location**: `apps/opik-frontend/src/components/pages/AnnotationQueuesPage/`
- **Purpose**: Main admin interface for managing annotation queues
- **Features**:
  - List all annotation queues with status and progress
  - Create new annotation queues
  - Copy shareable links for SME access
  - Add items to queues
  - Delete queues

#### CreateAnnotationQueueDialog
- **Location**: `apps/opik-frontend/src/components/pages/AnnotationQueuesPage/CreateAnnotationQueueDialog.tsx`
- **Purpose**: Dialog for creating new annotation queues
- **Features**:
  - Form validation with Zod schema
  - Queue configuration options
  - Integration with backend API

### SME Interface

#### AnnotationPage
- **Location**: `apps/opik-frontend/src/components/pages/AnnotationPage/`
- **Purpose**: Distraction-free interface for SME annotation
- **Features**:
  - Clean, focused annotation interface
  - Keyboard shortcuts for efficient workflow
  - Progress tracking
  - Star rating system
  - Comment functionality
  - Skip/complete actions

### Integration Components

#### AddToQueueDialog
- **Location**: `apps/opik-frontend/src/components/pages-shared/traces/AddToQueueDialog/`
- **Purpose**: Dialog for adding traces/spans to annotation queues
- **Integration**: Embedded in TracesActionsPanel for bulk operations

## User Workflows

### Admin Workflow

1. **Create Annotation Queue**
   - Navigate to Human Feedback → Queues
   - Click "Create Queue"
   - Configure queue settings (name, description, metrics)
   - Save queue

2. **Add Items to Queue**
   - Go to Traces page
   - Select traces/spans
   - Click "Add to queue"
   - Choose target annotation queue
   - Confirm addition

3. **Share Queue with SMEs**
   - Copy share link from queue list
   - Send link to SMEs via email/communication channel

4. **Monitor Progress**
   - View queue progress in admin interface
   - Track completion rates
   - Review annotations (future feature)

### SME Workflow

1. **Access Annotation Interface**
   - Click on shared queue link
   - Enter SME identifier
   - Begin annotation session

2. **Annotate Items**
   - Review trace/thread content
   - Provide star rating (1-5)
   - Add optional comments
   - Submit annotation or skip item

3. **Keyboard Shortcuts**
   - `1-5`: Quick rating
   - `Ctrl+Enter`: Submit annotation
   - `Ctrl+S`: Skip item
   - `?`: Show help
   - `Escape`: Close help

## Configuration

### Environment Variables

**Backend:**
- Database connection settings
- CORS configuration for public API access

**Frontend:**
- API endpoint configuration
- Feature flags for Human Feedback system

### Feature Toggles

The Human Feedback system can be enabled/disabled through feature toggles:
- Navigation menu visibility
- API endpoint availability
- UI component rendering

## Security Considerations

### Access Control

1. **Admin Access**: Requires workspace authentication
2. **SME Access**: Public endpoints with SME identifier
3. **Data Isolation**: Queues are isolated by workspace/project
4. **Input Validation**: All inputs validated on both client and server

### Data Privacy

1. **No PII Storage**: SME identifiers are simple strings
2. **Secure Transmission**: HTTPS for all API calls
3. **Data Retention**: Configurable retention policies
4. **Audit Trail**: All actions logged for compliance

## Testing

### Backend Tests

- **Unit Tests**: Service layer logic testing
- **Integration Tests**: Database operations and API endpoints
- **Test Coverage**: Core functionality and edge cases

### Frontend Tests

- **Component Tests**: UI component behavior
- **Integration Tests**: API interaction and state management
- **E2E Tests**: Complete user workflows

### Test Files

**Backend:**
- `AnnotationQueueServiceTest.java`
- `QueueItemServiceTest.java`
- `AnnotationServiceTest.java`

**Frontend:**
- `AnnotationQueuesPage.test.tsx`
- `AnnotationPage.test.tsx`
- `AddToQueueDialog.test.tsx`

## Performance Considerations

### Database Optimization

1. **Indexing**: Strategic indexes on frequently queried columns
2. **Pagination**: All list endpoints support pagination
3. **Query Optimization**: Efficient joins and filtering

### Frontend Optimization

1. **Lazy Loading**: Components loaded on demand
2. **Caching**: React Query for intelligent data caching
3. **Debouncing**: Search inputs debounced to reduce API calls

### Scalability

1. **Horizontal Scaling**: Stateless API design
2. **Database Partitioning**: Ready for table partitioning by workspace
3. **CDN Integration**: Static assets served via CDN

## Monitoring and Analytics

### Metrics to Track

1. **Queue Metrics**:
   - Queue creation rate
   - Item addition rate
   - Completion rates
   - Average annotation time

2. **SME Metrics**:
   - Active SME count
   - Annotation quality scores
   - Session duration
   - Skip rates

3. **System Metrics**:
   - API response times
   - Error rates
   - Database performance
   - User engagement

### Logging

1. **Structured Logging**: JSON format for easy parsing
2. **Correlation IDs**: Track requests across services
3. **Error Tracking**: Comprehensive error logging
4. **Audit Logs**: All admin actions logged

## Future Enhancements

### Phase 2 Features

1. **SME Invite System**: Email-based SME invitations
2. **Annotation Templates**: Predefined annotation schemas
3. **Advanced Analytics**: Detailed reporting and insights
4. **Batch Operations**: Bulk annotation capabilities

### Phase 3 Features

1. **Machine Learning Integration**: Auto-annotation suggestions
2. **Quality Scoring**: Inter-annotator agreement metrics
3. **Advanced Workflows**: Multi-stage annotation processes
4. **API Webhooks**: Real-time notifications

## Troubleshooting

### Common Issues

1. **Queue Not Loading**: Check database connectivity and permissions
2. **SME Access Denied**: Verify queue status and share URL
3. **Annotation Not Saving**: Check network connectivity and API status
4. **Performance Issues**: Review database indexes and query performance

### Debug Steps

1. **Check Logs**: Review application and database logs
2. **Verify Configuration**: Ensure all environment variables are set
3. **Test API Endpoints**: Use API testing tools to verify functionality
4. **Database Health**: Check database connection and query performance

## Support and Maintenance

### Regular Maintenance

1. **Database Cleanup**: Archive completed queues periodically
2. **Performance Monitoring**: Regular performance reviews
3. **Security Updates**: Keep dependencies updated
4. **Backup Procedures**: Regular database backups

### Support Channels

1. **Documentation**: This comprehensive guide
2. **Code Comments**: Inline documentation in codebase
3. **API Documentation**: OpenAPI/Swagger specifications
4. **Team Knowledge**: Development team expertise

---

*This documentation covers the complete Human Feedback system implementation. For specific technical details, refer to the source code and inline comments.*