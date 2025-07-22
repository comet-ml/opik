# Custom Dashboard Builder for Opik

## Overview

This is a comprehensive dashboard builder system designed specifically for Opik's ML observability platform. It provides a drag-and-drop interface for creating custom dashboards with real-time data visualization.

## Features

### ✅ Completed Core Features

1. **Dashboard Builder Interface**
   - ✅ Drag-and-drop grid layout system using react-grid-layout
   - ✅ Widget library with 6 different chart types
   - ✅ Real-time preview while building
   - ✅ Save/load dashboard configurations

2. **Widget Types Implemented**
   - ✅ Line charts (time series data)
   - ✅ Bar charts (categorical data) 
   - ✅ Pie charts (proportional data)
   - ✅ Data tables (tabular data)
   - ✅ KPI cards (single metrics with trend indicators)
   - ✅ Heatmaps (correlation/distribution data)

3. **Data Integration**
   - ✅ REST API integration with configurable endpoints
   - ✅ Support for query parameters (filters, date ranges, aggregations)
   - ✅ Real-time data updates with configurable refresh intervals
   - ✅ Comprehensive error handling and loading states
   - ✅ Mock data generation for development

4. **Configuration Management**
   - ✅ Save dashboard configurations as JSON
   - ✅ Dashboard CRUD operations
   - ✅ Import/export functionality (architecture ready)
   - ✅ Dashboard duplication

## Architecture

### Technology Stack
- **Frontend**: React 18 + TypeScript
- **Styling**: Tailwind CSS with existing UI components
- **Charts**: Recharts
- **Layout**: react-grid-layout for drag-and-drop
- **State Management**: Zustand (already in project)
- **HTTP Client**: Axios with React Query for caching
- **Icons**: Lucide React (already in project)
- **Validation**: Zod

### Project Structure

```
src/
├── components/
│   ├── dashboard/
│   │   ├── DashboardBuilder.tsx      # Main builder interface
│   │   ├── DashboardRenderer.tsx     # View-only dashboard display  
│   │   ├── WidgetLibrary.tsx         # Available widget types
│   │   └── ConfigPanel.tsx           # Widget configuration sidebar
│   ├── widgets/
│   │   ├── BaseWidget.tsx            # Common widget wrapper
│   │   ├── LineChart.tsx             # Time series visualization
│   │   ├── BarChart.tsx              # Categorical data charts
│   │   ├── PieChart.tsx              # Proportional data display
│   │   ├── DataTable.tsx             # Tabular data with sorting/pagination
│   │   ├── KPICard.tsx               # Key metrics with trends
│   │   └── Heatmap.tsx               # Correlation matrix visualization
│   └── pages/
│       └── DashboardPage/            # Main dashboard management page
├── hooks/
│   ├── useDashboard.ts              # Dashboard CRUD operations
│   ├── useWidgetData.ts             # Data fetching for widgets
│   └── useGridLayout.ts             # Grid layout management
├── services/
│   ├── api.ts                       # API client configuration
│   ├── dashboardService.ts          # Dashboard API calls
│   └── dataService.ts               # Widget data fetching
├── types/
│   ├── dashboard.ts                 # Dashboard type definitions
│   ├── widget.ts                    # Widget type definitions
│   └── api.ts                       # API response types
├── utils/
│   ├── chartHelpers.ts              # Chart data transformation
│   ├── gridHelpers.ts               # Grid layout utilities
│   └── validation.ts                # Configuration validation
└── lib/
    └── demo-dashboards.ts           # Sample dashboard configurations
```

## Getting Started

### Prerequisites
- Node.js 18+
- npm or yarn

### Installation

1. **Dependencies are already installed** - The project includes all necessary dependencies:
   - `react-grid-layout` for drag-and-drop functionality
   - `recharts` for charts
   - `@tanstack/react-query` for data fetching
   - `zustand` for state management
   - `zod` for validation

2. **CSS Setup** - Grid layout styles have been added to `src/main.scss`

3. **Routes** - Dashboard routes have been added to the router:
   - `/[workspace]/dashboards` - Main dashboard page

### Usage

#### Accessing Dashboards
Navigate to `/{workspaceName}/dashboards` in your Opik application.

#### Creating a Dashboard
1. Click "Create Dashboard" 
2. Enter name and description
3. Start adding widgets from the library on the left
4. Configure each widget by clicking the menu (⋮) and selecting "Edit"
5. Save your dashboard

#### Widget Configuration
Each widget can be configured with:
- **General**: Title, position, size
- **Data**: API endpoint, query parameters, refresh interval
- **Appearance**: Colors, legend, grid, chart-specific options

#### Real-time Data
- Widgets automatically refresh based on configured intervals
- Manual refresh available via widget menus or dashboard toolbar
- Auto-refresh can be controlled globally

## API Integration

### Dashboard Schema
```json
{
  "id": "string",
  "name": "string", 
  "description": "string",
  "layout": {
    "grid": [
      {
        "id": "string",
        "x": 0, "y": 0, "w": 6, "h": 4,
        "type": "line_chart" | "bar_chart" | "pie_chart" | "table" | "kpi_card" | "heatmap",
        "config": {
          "title": "string",
          "dataSource": "string",
          "queryParams": {},
          "chartOptions": {}
        }
      }
    ]
  },
  "filters": {
    "global": {}
  },
  "refreshInterval": 30,
  "created": "ISO date",
  "modified": "ISO date"
}
```

### Expected API Endpoints

The dashboard system expects these endpoints to be implemented:

- `GET /api/dashboards` - List dashboards
- `GET /api/dashboards/{id}` - Get dashboard
- `POST /api/dashboards` - Create dashboard
- `PATCH /api/dashboards/{id}` - Update dashboard
- `DELETE /api/dashboards/{id}` - Delete dashboard

### Widget Data Endpoints
Widgets can connect to any REST endpoint that returns data in these formats:

**Time Series Data:**
```json
{
  "data": [
    {"timestamp": "2024-01-01T00:00:00Z", "value": 100, "series": "requests"}
  ]
}
```

**Categorical Data:**
```json
{
  "data": [
    {"category": "success", "count": 850}
  ]
}
```

**Table Data:**
```json
{
  "data": [
    {"id": 1, "name": "Trace A", "duration": 245, "status": "success"}
  ],
  "pagination": {"page": 1, "totalPages": 10, "totalItems": 100}
}
```

## Development Mode

The system includes mock data generators for development:
- Automatic mock data generation for all widget types
- Configurable refresh intervals for demo purposes
- Sample dashboard configurations in `src/lib/demo-dashboards.ts`

## Performance Features

- ✅ Lazy loading for off-screen widgets
- ✅ React Query caching for API calls
- ✅ Debounced configuration changes
- ✅ Optimized chart re-renders with React.memo
- ✅ Responsive design for mobile/tablet

## Accessibility

- ✅ ARIA labels for interactive elements
- ✅ Keyboard navigation support
- ✅ Screen reader friendly
- ✅ High contrast mode support

## Browser Support

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

## Roadmap / Future Enhancements

### Phase 2 (Future)
- [ ] Dashboard templating system
- [ ] Advanced filtering (SQL-like query builder)
- [ ] Dashboard embedding (iframe/widget)
- [ ] Collaborative editing
- [ ] Dark/light theme support
- [ ] Keyboard shortcuts
- [ ] Advanced widget types (gauges, sparklines, etc.)
- [ ] Custom widget development framework

### Phase 3 (Future)
- [ ] Real-time collaboration
- [ ] Version history and rollback
- [ ] Advanced permissions system
- [ ] Widget marketplace
- [ ] Custom CSS/theming
- [ ] Export to PDF/PNG
- [ ] Scheduled reports

## Troubleshooting

### Common Issues

1. **Widgets not loading**: Check API endpoints and network requests
2. **Layout issues**: Verify react-grid-layout CSS is loaded
3. **Type errors**: Ensure all imports use correct paths with `@/` alias

### Development Tips

1. Use the demo dashboard configurations for testing
2. Enable React Query devtools for debugging data fetching
3. Check browser console for validation errors
4. Use the grid layout devtools for positioning issues

## Contributing

When extending the dashboard system:

1. Follow the existing widget pattern in `BaseWidget`
2. Add proper TypeScript types
3. Include error handling and loading states
4. Add mock data generators for development
5. Update this README with new features

## License

This dashboard system is part of the Opik project and follows the same license terms.
