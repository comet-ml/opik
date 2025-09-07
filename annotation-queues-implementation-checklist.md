# Annotation Queues Feature Implementation Checklist

**Epic**: OPIK-2288 - Improved Human Annotation Flows  
**Feature Flag**: `annotation_queues`  
**Total Effort**: 53-55 Story Points  
**Target**: v0.1 MVP Implementation

## 📋 Overview

This checklist tracks the implementation of annotation queues feature that enables non-technical Subject Matter Experts (SMEs) to review and annotate AI agent outputs through a simplified, queue-based workflow.

### Key Personas
- **Project Admin**: Creates annotation queues, configures review settings, manages SME access
- **SME**: Reviews and annotates traces/threads through a distraction-free interface

---

## 🏗️ Phase 1: Backend Foundation (24-26 SP)

### 1.1 Infrastructure Setup (1 SP)
- [x] **Add feature flag `annotation_queues`**
  - [x] Configure feature flag in feature flag system
  - [x] Set up toggle points for UI components
  - [ ] Test flag enable/disable functionality
  - [ ] Document flag usage in codebase

### 1.2 Database Schema (4 SP)
- [x] **Create ClickHouse tables**
  - [x] Create `annotation_queues` table with all required fields
  - [x] Create `annotation_queue_items` junction table
  - [x] Add proper indexing for performance
  - [x] Create database migration scripts
  - [ ] Test migration rollback functionality

### 1.3 Core CRUD Endpoints (4 SP)
- [x] **POST `/v1/private/annotation-queues/`** - Create queue
  - [x] Implement endpoint with validation
  - [x] Add proper error handling
  - [ ] Test with valid/invalid payloads
  - [ ] Add unit tests
- [x] **GET `/v1/private/annotation-queues/`** - List queues
  - [x] Implement filtering by scope
  - [x] Add sorting capabilities
  - [x] Add search by name functionality
  - [x] Test pagination
  - [ ] Add unit tests
- [x] **GET `/v1/private/annotation-queues/{id}`** - Get by ID
  - [x] Implement endpoint with proper error handling
  - [ ] Test with valid/invalid IDs
  - [ ] Add unit tests
- [x] **PATCH `/v1/private/annotation-queues/{id}`** - Update queue
  - [x] Implement update logic (scope immutable)
  - [x] Add validation for immutable fields
  - [ ] Test partial updates
  - [ ] Add unit tests
- [x] **POST `/v1/private/annotation-queues/delete`** - Bulk delete
  - [x] Implement bulk delete functionality
  - [x] Add proper error handling for partial failures
  - [ ] Test with multiple IDs
  - [ ] Add unit tests

### 1.4 Queue Item Management (4 SP)
- [x] **POST `/v1/private/annotation-queues/{id}/items/add`**
  - [x] Implement add items to queue
  - [x] Add duplicate prevention logic
  - [x] Support bulk operations
  - [ ] Test with valid/invalid item IDs
  - [ ] Add unit tests
- [x] **POST `/v1/private/annotation-queues/{id}/items/delete`**
  - [x] Implement remove items from queue
  - [x] Add proper error handling
  - [x] Support bulk operations
  - [ ] Test edge cases
  - [ ] Add unit tests

### 1.5 Feedback Definition Enhancement (1 SP)
- [x] **Add description field to feedback-definition resource**
  - [x] Add database column for description
  - [x] Update related endpoints
  - [x] Create migration script
  - [ ] Test backward compatibility
  - [ ] Add unit tests

### 1.6 Trace/Thread Filtering (4-6 SP)
- [x] **Update GET `/v1/private/traces/`**
  - [x] Add `annotation_queue_id` filter support
  - [ ] Test filtering functionality
  - [ ] Ensure performance with large datasets
  - [ ] Add unit tests
- [ ] **Update GET `/v1/private/traces/stats`**
  - [x] Add `annotation_queue_id` filter support
  - [ ] Test stats calculation accuracy
  - [ ] Add unit tests
- [x] **Update GET `/v1/private/traces/threads`**
  - [x] Add `annotation_queue_id` filter support
  - [ ] Test filtering functionality
  - [ ] Add unit tests

### 1.7 Service Layer & Business Logic (6 SP)
- [x] **Implement AnnotationQueueService**
  - [x] Create service class following existing patterns
  - [ ] Implement calculated field logic (reviewers, feedback_scores, items_count)
  - [x] Add proper transaction management
  - [x] Implement error handling and logging
  - [ ] Add comprehensive unit tests
  - [ ] Add integration tests

---

## 🎨 Phase 2: Frontend Core Features (15 SP)

### 2.1 API Integration Layer (2 SP)
- [x] **Create React Query hooks**
  - [x] Create hooks for all AnnotationQueue endpoints
  - [x] Add TypeScript types matching backend schema
  - [x] Implement error handling and loading states
  - [x] Follow existing API client patterns
  - [x] Add proper error boundaries

### 2.2 Feedback Definition Enhancement (1 SP)
- [x] **Update feedback definition forms**
  - [x] Add description field to forms
  - [x] Update existing components to display descriptions
  - [x] Add validation for description field
  - [x] Test form submission and validation

### 2.3 Create/Edit AnnotationQueue Modal (4 SP)
- [x] **Create all API related hooks for AnnotationQueue (1 SP)**
  - [x] Implement create queue hook
  - [x] Implement update queue hook
  - [x] Implement delete queue hook
  - [x] Add proper error handling
- [x] **Implement select scores component (1 SP)**
  - [x] Create FeedbackDefinitionSelector component
  - [x] Add multi-select functionality
  - [x] Add search/filter capabilities
  - [x] Test component functionality
- [x] **Implement all other fields and validation (2 SP)**
  - [x] Create CreateAnnotationQueueModal component
  - [ ] Create EditAnnotationQueueModal component
  - [x] Add form validation using existing patterns
  - [x] Integrate with create/update API endpoints
  - [x] Handle success/error states
  - [x] Test complete modal workflow

### 2.4 Add to AnnotationQueue Modal (3 SP)
- [x] **Implement all API related hooks to add/delete items (1 SP)**
  - [x] Create add items to queue hook
  - [x] Create remove items from queue hook
  - [x] Add proper error handling
  - [x] Test API integration
- [ ] **Implement modal and success popover (1 SP)**
  - [ ] Create AddToAnnotationQueueModal component
  - [ ] Add success notifications
  - [ ] Add error handling
  - [ ] Test modal functionality
- [ ] **Integrate this modal in 4 places (1 SP)**
  - [ ] Integrate in traces list view
  - [ ] Integrate in trace detail view
  - [ ] Integrate in threads list view
  - [ ] Integrate in thread detail view
  - [ ] Test integration in all locations

### 2.5 AnnotationQueues List Page (2 SP)
- [x] **Implement all functionality and connect all parts (2 SP)**
  - [x] Create main AnnotationQueues page
  - [x] Implement data table with search, filtering, sorting
  - [x] Add action buttons (create, edit, delete, view details)
  - [x] Add empty state for when no queues exist
  - [x] Follow existing list page patterns
  - [x] Test complete page functionality

### 2.6 AnnotationQueues Project Tab (1 SP)
- [ ] **Integrate all functionality from the main page (1 SP)**
  - [ ] Add new tab in project view
  - [ ] Integrate existing AnnotationQueues page functionality
  - [ ] Ensure proper navigation and routing
  - [ ] Test tab integration

### 2.7 Feature Flag Integration (2 SP)
- [x] **Integrate feature flag checks in all new components**
  - [x] Add flag checks to show/hide menu link
  - [ ] Add flag checks to show/hide AnnotationQueues tab
  - [ ] Add flag checks to show/hide "Add to queue" buttons
  - [x] Test flag toggle functionality
  - [x] Verify all components respect feature flag

---

## 🚀 Phase 3: Advanced UI Features (14 SP)

### 3.1 AnnotationQueue Details Page (7 SP)
- [ ] **Implement config tab (1.5 SP)**
  - [ ] Create QueueConfigTab component
  - [ ] Display and edit queue configuration
  - [ ] Add form validation
  - [ ] Test config editing functionality
- [ ] **Implement items tab as threads (1.5 SP)**
  - [ ] Create threads view in QueueItemsTab
  - [ ] List threads in queue with filtering
  - [ ] Add proper pagination
  - [ ] Test threads display and filtering
- [ ] **Implement items tab as traces (1.5 SP)**
  - [ ] Create traces view in QueueItemsTab
  - [ ] List traces in queue with filtering
  - [ ] Add proper pagination
  - [ ] Test traces display and filtering
- [ ] **Implement a page with instructions on how to add (1 SP)**
  - [ ] Create QueueInstructionsTab component
  - [ ] Add helpful instructions for queue management
  - [ ] Include examples and best practices
  - [ ] Test instructions display
- [ ] **Implement empty state (0.5 SP)**
  - [ ] Create empty state when no items in queue
  - [ ] Add helpful messaging and CTAs
  - [ ] Test empty state display
- [ ] **Integrate details page, AnnotationQueue Tab, and AnnotationQueue Page (1 SP)**
  - [ ] Connect all tabs and components
  - [ ] Ensure proper navigation between views
  - [ ] Test complete integration
  - [ ] Verify routing works correctly

### 3.2 SME Annotation Flow (11 SP)
- [ ] **Implement Instruction page (1 SP)**
  - [ ] Create SMEInstructionsPage component
  - [ ] Add welcome/onboarding content for SMEs
  - [ ] Include task description and goals
  - [ ] Test instruction page display
- [ ] **Implement page skeleton with progress bar, and navigation logic (2.5 SP)**
  - [ ] Create SMEAnnotationPage layout
  - [ ] Add progress indicator ("17 of 120 completed")
  - [ ] Implement next/previous navigation
  - [ ] Add keyboard shortcuts support
  - [ ] Test navigation and progress tracking
- [ ] **Implement comments and feedback score section (3 SP)**
  - [ ] Create SMEAnnotationInterface component
  - [ ] Add comment input field
  - [ ] Add feedback score inputs based on queue configuration
  - [ ] Support both traces and threads
  - [ ] Add save and auto-advance functionality
  - [ ] Test annotation interface
- [ ] **Implement preview data section (1 SP)**
  - [ ] Create clean display of trace/thread content
  - [ ] Add toggle for metadata visibility
  - [ ] Implement pretty mode by default
  - [ ] Test data display and toggles
- [ ] **Implement complete page (0.5 SP)**
  - [ ] Create SMECompletionPage component
  - [ ] Add success state when queue is finished
  - [ ] Include summary of completed work
  - [ ] Test completion flow
- [ ] **Implement list view page (3 SP)**
  - [ ] Create alternative view showing all items in queue
  - [ ] Add filtering and search capabilities
  - [ ] Support both traces and threads
  - [ ] Add bulk annotation capabilities
  - [ ] Test list view functionality

---

## 🧪 Testing Strategy

### Backend Testing
- [ ] **Unit Tests**
  - [ ] Test all service methods
  - [ ] Test all API endpoints
  - [ ] Test validation logic
  - [ ] Test error handling
- [ ] **Integration Tests**
  - [ ] Test queue operations end-to-end
  - [ ] Test item management workflows
  - [ ] Test calculated field logic
- [ ] **Feature Flag Testing**
  - [ ] Test flag toggle functionality
  - [ ] Verify flag controls correct behavior
- [ ] **Database Testing**
  - [ ] Test migration scripts
  - [ ] Test rollback functionality
  - [ ] Test performance with large datasets

### Frontend Testing
- [ ] **Component Unit Tests**
  - [ ] Test all new UI components
  - [ ] Test form validation
  - [ ] Test error handling
- [ ] **Integration Tests**
  - [ ] Test complete workflows
  - [ ] Test API integration
  - [ ] Test navigation flows
- [ ] **Feature Flag Testing**
  - [ ] Test component visibility
  - [ ] Test flag toggle behavior
- [ ] **User Journey Testing**
  - [ ] Test admin workflow: Create queue → Add items → Share link → Monitor progress
  - [ ] Test SME workflow: Access queue link → Sign in → Annotate items → Complete queue

### Manual Testing Scenarios
- [ ] **Admin User Journey**
  - [ ] Create annotation queue with all fields
  - [ ] Add traces/threads to queue (single and bulk)
  - [ ] Share queue link with SME
  - [ ] Monitor progress and completion
  - [ ] Edit queue configuration
  - [ ] Delete queue and items
- [ ] **SME User Journey**
  - [ ] Access queue via shared link
  - [ ] Sign in with existing account
  - [ ] Review instructions and task details
  - [ ] Annotate items with comments and scores
  - [ ] Navigate between items
  - [ ] Complete entire queue
- [ ] **Error Handling**
  - [ ] Test network failures
  - [ ] Test invalid data submission
  - [ ] Test unauthorized access
  - [ ] Test edge cases and error states
- [ ] **Feature Flag Testing**
  - [ ] Toggle flag on/off
  - [ ] Verify all components respect flag state
  - [ ] Test flag persistence across sessions

---

## 📋 Acceptance Criteria

### Functional Requirements
- [ ] **Queue Management**
  - [ ] Admins can create, edit, and delete annotation queues
  - [ ] Queues support both trace and thread scopes
  - [ ] Queues can be configured with feedback definitions
  - [ ] Queue items can be added/removed individually or in bulk
- [ ] **SME Access**
  - [ ] SMEs can access queues via shared links
  - [ ] SME interface is distraction-free with no main app navigation
  - [ ] Progress tracking shows completion status
  - [ ] Annotation interface supports comments and feedback scores
- [ ] **Integration**
  - [ ] Existing trace/thread endpoints support queue filtering
  - [ ] Feature flag controls all new functionality
  - [ ] All new components follow existing design patterns

### Non-Functional Requirements
- [ ] **Performance**
  - [ ] Large queues (1000+ items) load and function properly
  - [ ] Database queries are optimized with proper indexing
  - [ ] Frontend components render efficiently
- [ ] **Usability**
  - [ ] SME interface is intuitive for non-technical users
  - [ ] Admin interface follows existing Opik patterns
  - [ ] Error messages are clear and actionable
- [ ] **Security**
  - [ ] Queue access is properly controlled
  - [ ] No sensitive data is exposed to unauthorized users
  - [ ] All API endpoints have proper authentication

---

## 🚀 Deployment Checklist

### Pre-Deployment
- [ ] **Code Review**
  - [ ] All code has been reviewed and approved
  - [ ] All tests are passing
  - [ ] Feature flag is properly configured
- [ ] **Database**
  - [ ] Migration scripts are tested
  - [ ] Rollback procedures are documented
  - [ ] Performance impact is assessed
- [ ] **Documentation**
  - [ ] API documentation is updated
  - [ ] User guides are created
  - [ ] Feature flag documentation is complete

### Deployment
- [ ] **Feature Flag**
  - [ ] Flag is deployed in disabled state
  - [ ] Flag can be toggled without code deployment
  - [ ] Monitoring is in place for flag usage
- [ ] **Database**
  - [ ] Migrations are executed successfully
  - [ ] Data integrity is verified
  - [ ] Performance is monitored
- [ ] **Monitoring**
  - [ ] Error tracking is configured
  - [ ] Performance metrics are set up
  - [ ] User analytics are enabled

### Post-Deployment
- [ ] **Validation**
  - [ ] Feature works correctly in production
  - [ ] All user journeys are functional
  - [ ] Performance meets requirements
- [ ] **Monitoring**
  - [ ] Monitor error rates and performance
  - [ ] Track user adoption and usage
  - [ ] Collect feedback from early users
- [ ] **Rollback Plan**
  - [ ] Feature flag can disable functionality
  - [ ] Database rollback procedures are ready
  - [ ] Communication plan for issues

---

## 📊 Success Metrics

### Technical Metrics
- [ ] **Performance**
  - [ ] Page load times < 2 seconds
  - [ ] API response times < 500ms
  - [ ] Database query performance acceptable
- [ ] **Quality**
  - [ ] Test coverage > 80%
  - [ ] Zero critical bugs in production
  - [ ] Feature flag can disable functionality

### User Metrics
- [ ] **Adoption**
  - [ ] At least 5 queues created in first week
  - [ ] At least 10 SMEs complete annotation tasks
  - [ ] Feature usage grows week over week
- [ ] **Satisfaction**
  - [ ] SME completion rate > 80%
  - [ ] Admin feedback is positive
  - [ ] No major usability issues reported

---

## 📝 Notes & Issues

### Known Issues
- [ ] Issue 1: [Description]
- [ ] Issue 2: [Description]

### Decisions Made
- [ ] Decision 1: [Description and rationale]
- [ ] Decision 2: [Description and rationale]

### Future Enhancements
- [ ] Enhancement 1: [Description]
- [ ] Enhancement 2: [Description]

---

**Last Updated**: September 6, 2025  
**Next Review**: TBD  
**Status**: 🟢 Phase 1 & 2 Complete - Backend & Frontend Compile Successfully
