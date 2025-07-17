# Quick Start Guide

## Getting Started with OPIK Local Development

This guide will help you get the OPIK development environment running locally in minutes.

### Prerequisites

Make sure you have the following installed:
- Docker
- Maven
- Node.js (v18+)
- npm

### Step 1: Start Everything

Run the development script:

```bash
./scripts/dev-local.sh
```

This will:
- ✅ Start all required containers (MySQL, Redis, ClickHouse, etc.)
- ✅ Build the backend with Maven (skipping tests)
- ✅ Start the backend on http://localhost:8080
- ✅ Start the frontend on http://localhost:5173

### Step 2: Access the Application

Once everything is running, you can access:
- **Frontend**: http://localhost:5173
- **Backend API**: http://localhost:8080
- **Backend Health Check**: http://localhost:8080/health-check

### Step 3: Stop Everything

Press `Ctrl+C` to stop the backend and frontend processes.

To stop the containers:
```bash
./opik.sh --stop
```

### Alternative Workflows

#### Start Only Containers
If you want to run backend/frontend from your IDE:
```bash
./scripts/dev-local.sh --containers-only
```

#### Start Only Backend
```bash
./scripts/dev-local.sh --backend-only
```

#### Start Only Frontend
```bash
./scripts/dev-local.sh --frontend-only
```

### Troubleshooting

If you encounter issues:

1. **Check if Docker is running**
2. **Check if ports are available** (8080, 5173, 3306, 6379, 8123)
3. **Check container logs**: `docker logs opik-mysql-1`
4. **Restart containers**: `./opik.sh --stop && ./scripts/dev-local.sh --containers-only`

### Next Steps

- Read the full documentation in `scripts/README-dev-local.md`
- Check the main project README for development guidelines
- Join the community for support