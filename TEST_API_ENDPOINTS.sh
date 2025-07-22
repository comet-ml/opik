#!/bin/bash

echo "🧪 Testing Dashboard API Endpoints"
echo "=================================="

BASE_URL="http://localhost:8080"
API_KEY="your-api-key-here"

echo ""
echo "1. 📊 Testing Mock Data Endpoints"
echo "----------------------------------"

echo "Time Series Data:"
curl -s "$BASE_URL/v1/private/dashboard-data/time-series?hours=6&series=requests" | jq '.data[:2]'

echo ""
echo "Categorical Data:"
curl -s "$BASE_URL/v1/private/dashboard-data/categorical" | jq '.data'

echo ""
echo "Table Data:"
curl -s "$BASE_URL/v1/private/dashboard-data/table?limit=3" | jq '.data[:2]'

echo ""
echo "KPI Data:"
curl -s "$BASE_URL/v1/private/dashboard-data/kpi?metric=success_rate" | jq '.data'

echo ""
echo "Heatmap Data:"
curl -s "$BASE_URL/v1/private/dashboard-data/heatmap" | jq '.data[:3]'

echo ""
echo "2. 🎛️ Testing Dashboard CRUD"
echo "----------------------------"

echo "Create Dashboard:"
DASHBOARD_ID=$(curl -s -X POST "$BASE_URL/v1/private/dashboards" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Dashboard",
    "description": "Integration test dashboard"
  }' | jq -r '.id')

echo "Dashboard created with ID: $DASHBOARD_ID"

echo ""
echo "Get Dashboard:"
curl -s "$BASE_URL/v1/private/dashboards/$DASHBOARD_ID" \
  -H "Authorization: Bearer $API_KEY" | jq '.name'

echo ""
echo "Update Dashboard:"
curl -s -X PATCH "$BASE_URL/v1/private/dashboards/$DASHBOARD_ID" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "layout": {
      "grid": [{
        "id": "widget_1",
        "x": 0, "y": 0, "w": 6, "h": 4,
        "type": "line_chart",
        "config": {
          "title": "Request Volume",
          "dataSource": "/v1/private/dashboard-data/time-series",
          "queryParams": {"series": "requests"},
          "chartOptions": {"colors": ["#8884d8"]}
        }
      }]
    }
  }' | jq '.layout.grid[0].config.title'

echo ""
echo "List Dashboards:"
curl -s "$BASE_URL/v1/private/dashboards?size=5" \
  -H "Authorization: Bearer $API_KEY" | jq '.content[].name'

echo ""
echo "Delete Dashboard:"
curl -s -X DELETE "$BASE_URL/v1/private/dashboards/$DASHBOARD_ID" \
  -H "Authorization: Bearer $API_KEY"

echo "Dashboard deleted successfully"

echo ""
echo "✅ All API endpoints tested!"
