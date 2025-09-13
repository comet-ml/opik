# SME View Implementation Plan

**Epic**: OPIK-2288 - Improved Human Annotation Flows  
**Feature**: SME (Subject Matter Expert) Focused View for Annotation Queues  
**Feature Flag**: `annotation_queues` (existing)  
**Total Effort**: 12-15 Story Points  
**Target**: v0.1 MVP Implementation

## 📋 Overview

This plan implements a focused, distraction-free interface for SMEs to review and annotate AI agent outputs through shared queue links. The SME view will be a separate, minimal interface that allows non-technical users to efficiently process annotation tasks without access to the main Opik application.

**Note**: This plan builds upon the existing annotation queues implementation (Phase 1 & 2 complete) and adds the SME-focused view functionality.

### Key Personas
- **Project Admin**: Creates annotation queues, generates shareable links, monitors progress
- **SME**: Accesses queue via shared link, reviews and annotates items in a focused interface

### Design References
- **Get Started Page**: [Figma Design](https://www.figma.com/design/2xuDEboYKxgaVWmB39N41u/Annotation-Queues?node-id=2071-24505&m=dev)
- **Focused View**: [Figma Design](https://www.figma.com/design/2xuDEboYKxgaVWmB39N41u/Annotation-Queues?node-id=2082-22872&m=dev)

### Already Implemented (Phase 1 & 2 Complete)
- ✅ **Backend Foundation**: Complete CRUD API, database schema, service layer
- ✅ **Admin Interface**: Queue management, creation, editing, item management
- ✅ **Frontend Integration**: Full admin interface with data tables, modals, forms
- ✅ **Feature Flag**: `annotation_queues` flag implemented and working

### What This Plan Adds (SME View Only)
- 🆕 **Queue Sharing**: Generate shareable links for queues
- 🆕 **Public API Endpoints**: SME access without authentication
- 🆕 **SME Interface**: Focused, distraction-free annotation workflow
- 🆕 **Progress Tracking**: Real-time completion monitoring
- 🆕 **Admin Integration**: Share buttons and progress monitoring in existing UI

---

## 🏗️ Phase 1: Backend Foundation (4-5 SP)

### 1.1 Queue Sharing & Access Control (2 SP)
- [ ] **Add queue sharing fields to database**
  - [ ] Add `share_token` field to `annotation_queues` table (UUID, unique)
  - [ ] Add `is_public` boolean field (default: false)
  - [ ] Note: `created_by` field already exists in current schema
  - [ ] Create database migration script
  - [ ] Test migration rollback functionality

- [ ] **Implement queue sharing endpoints**
  - [ ] `POST /v1/private/annotation-queues/{id}/share` - Generate share token
  - [ ] `GET /v1/public/annotation-queues/{share_token}` - Get queue by share token (public endpoint)
  - [ ] `GET /v1/public/annotation-queues/{share_token}/items` - Get queue items (public endpoint)
  - [ ] `POST /v1/public/annotation-queues/{share_token}/items/{item_id}/annotate` - Submit annotation (public endpoint)
  - [ ] Add proper validation and error handling
  - [ ] Test all endpoints with valid/invalid tokens

### 1.2 SME Authentication (1 SP)
- [ ] **Implement minimal authentication for SME view**
  - [ ] Create `GET /v1/public/annotation-queues/{share_token}/auth/check` - Check if queue exists and is accessible
  - [ ] Implement optional email-based authentication for tracking
  - [ ] Add rate limiting for public endpoints
  - [ ] Test authentication flow

### 1.3 Annotation Submission (1-2 SP)
- [ ] **Implement annotation submission endpoints**
  - [ ] `POST /v1/public/annotation-queues/{share_token}/items/{item_id}/annotate` - Submit feedback scores and comments
  - [ ] `GET /v1/public/annotation-queues/{share_token}/progress` - Get completion progress
  - [ ] `GET /v1/public/annotation-queues/{share_token}/items/{item_id}` - Get specific item details
  - [ ] Add validation for feedback scores based on queue configuration
  - [ ] Test annotation submission and progress tracking

---

## 🎨 Phase 2: Frontend SME Interface (8-10 SP)

### 2.1 Routing & Layout Setup (1 SP)
- [ ] **Create SME-specific routing**
  - [ ] Add `/sme/queue/{shareToken}` route using `workspaceGuardEmptyLayoutRoute`
  - [ ] Create `SMEQueuePage` component as main entry point
  - [ ] Implement route parameter validation
  - [ ] Test routing and parameter handling

- [ ] **Create minimal SME layout**
  - [ ] Create `SMELayout` component with minimal header (Opik logo, progress indicator)
  - [ ] Remove all navigation elements (sidebar, user menu, etc.)
  - [ ] Add responsive design for mobile/tablet
  - [ ] Test layout on different screen sizes

### 2.2 Queue Access & Instructions (2 SP)
- [ ] **Implement queue access validation**
  - [ ] Create `useSMEQueue` hook to fetch queue data by share token
  - [ ] Add error handling for invalid/expired tokens
  - [ ] Implement loading states and error boundaries
  - [ ] Test error scenarios (invalid token, expired queue, etc.)

- [ ] **Create instructions page**
  - [ ] Create `SMEInstructionsPage` component based on Figma design
  - [ ] Display queue name, description, and task instructions
  - [ ] Show feedback definitions that need to be scored
  - [ ] Add "Start Annotating" button to proceed
  - [ ] Test instructions display and navigation

### 2.3 Annotation Interface (3-4 SP)
- [ ] **Create annotation page layout**
  - [ ] Create `SMEAnnotationPage` component with progress bar
  - [ ] Implement "X of Y completed" progress indicator
  - [ ] Add next/previous navigation buttons
  - [ ] Add keyboard shortcuts (arrow keys, space for next)
  - [ ] Test navigation and progress tracking

- [ ] **Implement feedback scoring interface**
  - [ ] Create `SMEFeedbackForm` component
  - [ ] Display feedback definitions with appropriate input types (slider, text, etc.)
  - [ ] Add comment field for each item
  - [ ] Implement form validation based on queue configuration
  - [ ] Test form submission and validation

- [ ] **Create item display component**
  - [ ] Create `SMEItemDisplay` component for traces/threads
  - [ ] Implement clean, readable display of trace/thread data
  - [ ] Add toggle for metadata visibility (pretty mode by default)
  - [ ] Support both trace and thread scopes
  - [ ] Test data display and formatting

### 2.4 Progress & Completion (1 SP)
- [ ] **Implement progress tracking**
  - [ ] Create `useSMEProgress` hook to track completion status
  - [ ] Update progress bar in real-time
  - [ ] Persist progress in localStorage for session continuity
  - [ ] Test progress tracking accuracy

- [ ] **Create completion page**
  - [ ] Create `SMECompletionPage` component
  - [ ] Display completion summary and statistics
  - [ ] Add "Return to Queue" button for review
  - [ ] Test completion flow and summary display

### 2.5 Mobile Responsiveness (1 SP)
- [ ] **Optimize for mobile devices**
  - [ ] Ensure touch-friendly interface elements
  - [ ] Optimize form layouts for small screens
  - [ ] Test on various mobile devices and screen sizes
  - [ ] Implement mobile-specific navigation patterns

---

## 🔧 Phase 3: Integration & Polish (2-3 SP)

### 3.1 Admin Integration (1 SP)
- [ ] **Add sharing functionality to admin interface**
  - [ ] Add "Share Queue" button to existing `AnnotationQueueRowActionsCell` component
  - [ ] Create `ShareQueueDialog` component with copy-to-clipboard functionality
  - [ ] Display share token and expiration information
  - [ ] Test sharing workflow from admin interface

- [ ] **Add progress monitoring**
  - [ ] Display completion statistics in existing queue details
  - [ ] Add real-time progress updates
  - [ ] Show SME activity and completion rates
  - [ ] Test progress monitoring accuracy

### 3.2 Error Handling & Edge Cases (1 SP)
- [ ] **Implement comprehensive error handling**
  - [ ] Handle network failures gracefully
  - [ ] Implement retry mechanisms for failed submissions
  - [ ] Add offline support with local storage
  - [ ] Test error scenarios and recovery

- [ ] **Handle edge cases**
  - [ ] Empty queues
  - [ ] Single-item queues
  - [ ] Already completed items
  - [ ] Concurrent access by multiple SMEs
  - [ ] Test all edge cases

### 3.3 Performance & Security (1 SP)
- [ ] **Optimize performance**
  - [ ] Implement lazy loading for large queues
  - [ ] Add caching for queue data
  - [ ] Optimize API calls and reduce payload size
  - [ ] Test performance with large datasets

- [ ] **Implement security measures**
  - [ ] Add rate limiting for public endpoints
  - [ ] Implement CSRF protection
  - [ ] Add input sanitization for user comments
  - [ ] Test security measures

---

## 🧪 Testing Strategy

### Backend Testing
- [ ] **Unit Tests**
  - [ ] Test all new API endpoints
  - [ ] Test authentication and authorization
  - [ ] Test annotation submission logic
  - [ ] Test progress calculation

- [ ] **Integration Tests**
  - [ ] Test complete SME workflow
  - [ ] Test admin sharing workflow
  - [ ] Test error scenarios
  - [ ] Test concurrent access

### Frontend Testing
- [ ] **Component Tests**
  - [ ] Test all SME components
  - [ ] Test form validation
  - [ ] Test navigation and routing
  - [ ] Test responsive design

- [ ] **E2E Tests**
  - [ ] Test complete SME journey
  - [ ] Test admin sharing workflow
  - [ ] Test error handling
  - [ ] Test mobile responsiveness

### Manual Testing Scenarios
- [ ] **Admin Workflow**
  - [ ] Create queue with feedback definitions
  - [ ] Generate share link
  - [ ] Monitor progress and completion
  - [ ] Test queue management

- [ ] **SME Workflow**
  - [ ] Access queue via shared link
  - [ ] Review instructions and task details
  - [ ] Annotate items with scores and comments
  - [ ] Complete entire queue
  - [ ] Test on mobile devices

---

## 📋 Acceptance Criteria

### Functional Requirements
- [ ] **Queue Sharing**
  - [ ] Admins can generate shareable links for queues
  - [ ] Share links provide access to focused SME interface
  - [ ] Progress is tracked and visible to admins
  - [ ] Queues can be made public or private

- [ ] **SME Interface**
  - [ ] Clean, distraction-free interface
  - [ ] Clear instructions and task guidance
  - [ ] Intuitive annotation workflow
  - [ ] Progress tracking and completion status
  - [ ] Mobile-responsive design

- [ ] **Annotation Workflow**
  - [ ] Support for all feedback definition types
  - [ ] Comment submission for each item
  - [ ] Form validation and error handling
  - [ ] Auto-save and session persistence

### Non-Functional Requirements
- [ ] **Performance**
  - [ ] Page load times < 2 seconds
  - [ ] Smooth navigation between items
  - [ ] Efficient handling of large queues
  - [ ] Mobile performance optimization

- [ ] **Usability**
  - [ ] Intuitive for non-technical users
  - [ ] Clear visual hierarchy and feedback
  - [ ] Accessible design patterns
  - [ ] Consistent with Opik design system

- [ ] **Security**
  - [ ] Secure token-based access
  - [ ] Rate limiting and abuse prevention
  - [ ] Input validation and sanitization
  - [ ] No sensitive data exposure

---

## 🚀 Implementation Phases

### Phase 1: Backend Foundation (Week 1-2)
- Implement queue sharing and access control
- Create public API endpoints for SME access
- Add authentication and security measures

### Phase 2: Frontend SME Interface (Week 3-4)
- Create minimal SME layout and routing
- Implement annotation interface and workflow
- Add mobile responsiveness and polish

### Phase 3: Integration & Testing (Week 5)
- Integrate with admin interface
- Comprehensive testing and bug fixes
- Performance optimization and security review

---

## 📊 Success Metrics

### Technical Metrics
- [ ] **Performance**
  - [ ] Page load times < 2 seconds
  - [ ] API response times < 500ms
  - [ ] Mobile performance score > 90

### User Metrics
- [ ] **Adoption**
  - [ ] At least 10 queues shared in first week
  - [ ] At least 20 SMEs complete annotation tasks
  - [ ] Average completion rate > 80%

- [ ] **Satisfaction**
  - [ ] SME feedback is positive
  - [ ] No major usability issues reported
  - [ ] Admin workflow is efficient

---

## 🔗 Related Files & Components

### Backend (Building on existing)
- `AnnotationQueueService.java` - Add sharing functionality to existing service
- `AnnotationQueueResource.java` - Add public endpoints to existing resource
- `AnnotationQueueDAO.java` - Add sharing methods to existing DAO
- Database migrations for sharing fields (extending existing schema)

### Frontend (New components)
- `SMEQueuePage.tsx` - Main SME entry point
- `SMELayout.tsx` - Minimal layout component
- `SMEAnnotationPage.tsx` - Annotation interface
- `SMEInstructionsPage.tsx` - Instructions display
- `SMECompletionPage.tsx` - Completion summary
- `ShareQueueDialog.tsx` - Admin sharing interface (extends existing admin UI)

### API Hooks (New)
- `useSMEQueue.ts` - Queue data fetching
- `useSMEProgress.ts` - Progress tracking
- `useSMEAnnotation.ts` - Annotation submission

### Existing Components (Integration points)
- `AnnotationQueueRowActionsCell.tsx` - Add share button
- `AnnotationQueuesPage.tsx` - Add progress monitoring
- `AnnotationQueuesTab.tsx` - Add progress monitoring

---

## 📝 Notes & Considerations

### Design Decisions
- **Minimal Interface**: Remove all unnecessary navigation and features
- **Mobile-First**: Prioritize mobile experience for accessibility
- **Progressive Enhancement**: Start with basic functionality, add features incrementally
- **Security**: Implement proper access control and rate limiting

### Future Enhancements
- [ ] Bulk annotation capabilities
- [ ] Advanced filtering and search
- [ ] Custom annotation workflows
- [ ] Integration with external tools
- [ ] Advanced analytics and reporting

---

**Last Updated**: January 2025  
**Next Review**: After testing completion  
**Status**: 🟢 Phase 1 & 2 Complete - Backend & Frontend Compile Successfully  

## ✅ Implementation Progress

### Completed ✅
- **Database Migration**: Added `share_token` and `is_public` fields
- **Backend API**: Share token generation and public endpoints
- **Frontend SME Interface**: Complete annotation workflow with routing
- **Admin Integration**: Share button added to queue actions
- **TypeScript Types**: Full type safety for SME functionality

### Remaining 🔄
- **Annotation Submission Logic**: Complete backend implementation for feedback storage
- **Progress Tracking**: Real-time completion calculation
- **Testing**: End-to-end workflow testing
- **Mobile Optimization**: Touch-friendly improvements
