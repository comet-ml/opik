# Multi-Scoring Feature Implementation

## Overview

The multi-scoring feature allows users to add multiple feedback scores for the same metric on a single entity (trace, span, or thread). This enables collaborative scoring, different perspectives, and more comprehensive evaluation of AI model outputs.

## Key Features

- **Multiple scores per metric**: Users can add multiple scores for the same metric without overwriting existing ones
- **Aggregated statistics**: Automatic calculation of average, minimum, maximum, and count for each metric
- **Individual score management**: Delete specific scores by their unique ID
- **Source tracking**: Each score maintains its source (UI, SDK, online_scoring)
- **User attribution**: Each score tracks who created and last updated it
- **Backward compatibility**: Existing single-scoring functionality continues to work

## Frontend Components

### 1. Types (`src/types/traces.ts`)

```typescript
// Enhanced TraceFeedbackScore with scoreId
export interface TraceFeedbackScore {
  category_name?: string;
  reason?: string;
  name: string;
  source: FEEDBACK_SCORE_TYPE;
  value: number;
  last_updated_by?: string;
  last_updated_at?: string;
  score_id?: string; // New field for multi-scoring
}

// New FeedbackScoreGroup interface
export interface FeedbackScoreGroup {
  name: string;
  category_name?: string;
  average_value: number;
  min_value: number;
  max_value: number;
  score_count: number;
  scores: TraceFeedbackScore[];
}
```

### 2. API Hooks

#### `useTraceFeedbackScoreGroups` (`src/api/traces/useTraceFeedbackScoreGroups.ts`)
Fetches feedback score groups for traces with multi-scoring support.

#### `useSpanFeedbackScoreGroups` (`src/api/spans/useSpanFeedbackScoreGroups.ts`)
Fetches feedback score groups for spans with multi-scoring support.

#### `useDeleteTraceFeedbackScore` (`src/api/traces/useDeleteTraceFeedbackScore.ts`)
Mutation hook to delete individual feedback scores for traces.

#### `useDeleteSpanFeedbackScore` (`src/api/spans/useDeleteSpanFeedbackScore.ts`)
Mutation hook to delete individual feedback scores for spans.

### 3. UI Components

#### `MultiScoringRow` (`src/components/pages-shared/traces/FeedbackScoresEditor/MultiScoringRow.tsx`)
Displays a feedback score group with:
- Aggregated statistics (average, min, max, count)
- Expandable list of individual scores
- Individual score deletion
- Add new score functionality

#### `MultiScoringFeedbackScoresEditor` (`src/components/pages-shared/traces/FeedbackScoresEditor/MultiScoringFeedbackScoresEditor.tsx`)
Enhanced feedback scores editor with:
- Tabbed interface for multi-scoring and single-scoring views
- Integration of both scoring systems
- Add score dialog
- Loading and error states

#### `TraceAnnotateViewerWithMultiScoring` (`src/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/TraceAnnotateViewerWithMultiScoring.tsx`)
Enhanced trace annotate viewer that integrates multi-scoring functionality.

#### `SpanAnnotateViewerWithMultiScoring` (`src/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/SpanAnnotateViewerWithMultiScoring.tsx`)
Enhanced span annotate viewer that integrates multi-scoring functionality.

### 4. Custom Hook

#### `useMultiScoring` (`src/hooks/useMultiScoring.ts`)
Custom hook that manages multi-scoring functionality for both traces and spans:
- Fetches feedback score groups
- Handles score deletion
- Manages loading and error states
- Provides add score functionality

## Usage Examples

### Basic Integration

```typescript
import TraceAnnotateViewerWithMultiScoring from '@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/TraceAnnotateViewerWithMultiScoring';

const TraceDetails = ({ trace }) => {
  const handleUpdateFeedbackScore = (update) => {
    // Handle score update
  };

  const handleDeleteFeedbackScore = (name) => {
    // Handle score deletion by name
  };

  return (
    <TraceAnnotateViewerWithMultiScoring
      trace={trace}
      onUpdateFeedbackScore={handleUpdateFeedbackScore}
      onDeleteFeedbackScore={handleDeleteFeedbackScore}
    />
  );
};
```

### Using the Custom Hook

```typescript
import useMultiScoring from '@/hooks/useMultiScoring';

const MyComponent = ({ entityId, entityType }) => {
  const {
    feedbackScoreGroups,
    isLoading,
    error,
    handleDeleteScore,
    handleAddScore,
    isDeleting,
  } = useMultiScoring({
    entityId,
    entityType, // "trace" or "span"
  });

  return (
    <div>
      {feedbackScoreGroups.map(group => (
        <div key={group.name}>
          <h3>{group.name}</h3>
          <p>Average: {group.average_value}</p>
          <p>Count: {group.score_count}</p>
        </div>
      ))}
    </div>
  );
};
```

## API Endpoints

### Get Feedback Score Groups
- `GET /v1/private/traces/{id}/feedback-scores` - Get trace feedback score groups
- `GET /v1/private/spans/{id}/feedback-scores` - Get span feedback score groups

### Delete Individual Score
- `DELETE /v1/private/traces/{id}/feedback-scores/{scoreId}` - Delete specific trace score
- `DELETE /v1/private/spans/{id}/feedback-scores/{scoreId}` - Delete specific span score

## UI Features

### Multi-Scoring View
- **Collapsible groups**: Each metric group can be expanded to show individual scores
- **Statistics display**: Shows average, min, max, and count for each group
- **Individual score cards**: Each score shows value, source, category, reason, and metadata
- **Delete functionality**: Individual scores can be deleted with confirmation
- **Add score button**: Allows adding new scores to existing groups

### Single-Scoring View
- **Legacy compatibility**: Maintains existing single-scoring interface
- **Tabbed interface**: Easy switching between multi-scoring and single-scoring views
- **Backward compatibility**: All existing functionality preserved

### Visual Indicators
- **Badge indicators**: Shows "Multi-scoring enabled" when multiple scores exist
- **Score count badges**: Displays the number of scores in each group
- **Source badges**: Different colors for different score sources (UI, SDK, online_scoring)
- **Loading states**: Skeleton loaders during data fetching
- **Error states**: User-friendly error messages

## Migration Strategy

### Phase 1: Backend Implementation ✅
- Database schema updates
- API endpoints for multi-scoring
- Backward compatibility maintained

### Phase 2: Frontend Implementation ✅
- New UI components
- API hooks for multi-scoring
- Integration with existing components

### Phase 3: Gradual Adoption
- Teams can opt-in to multi-scoring features
- Existing single-scoring continues to work
- Training and documentation for users

### Phase 4: Full Migration
- Multi-scoring becomes the default view
- Single-scoring view available as fallback
- Complete feature parity

## Benefits

1. **Collaborative Evaluation**: Multiple team members can contribute scores
2. **Comprehensive Assessment**: Different perspectives on the same metric
3. **Audit Trail**: Full history of scoring decisions
4. **Statistical Insights**: Aggregated views provide better insights
5. **Flexibility**: Users can choose between single and multi-scoring approaches

## Future Enhancements

1. **Score Weighting**: Allow different weights for different scores
2. **Score Confidence**: Add confidence levels to individual scores
3. **Score Comments**: Enhanced commenting system for scores
4. **Score Templates**: Predefined scoring templates for common scenarios
5. **Score Analytics**: Advanced analytics and visualization of scoring patterns
6. **Score Export**: Export scoring data for external analysis

## Testing

The implementation includes comprehensive tests covering:
- API endpoint functionality
- UI component behavior
- Data fetching and caching
- Error handling
- User interactions
- Backward compatibility

## Performance Considerations

- **Lazy loading**: Score groups are loaded on demand
- **Caching**: React Query provides efficient caching
- **Optimistic updates**: UI updates immediately for better UX
- **Debounced operations**: Prevents excessive API calls
- **Pagination**: Large score groups can be paginated if needed

## Accessibility

- **Keyboard navigation**: Full keyboard support for all interactions
- **Screen reader support**: Proper ARIA labels and descriptions
- **Focus management**: Logical focus flow through components
- **Color contrast**: Meets WCAG guidelines
- **Error announcements**: Screen reader announcements for errors