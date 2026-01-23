# Opik Architecture

## Technology Stack

### Backend (Java)
| Component | Technology | Version |
|-----------|------------|---------|
| Language | Java | 21 |
| Framework | Dropwizard | 4.0.14 |
| DI | Dropwizard-Guicey | 7.2.1 |
| Database Access | JDBI3 | - |
| Primary DB | MySQL | 9.3.0 |
| Analytics DB | ClickHouse | 0.9.0 |
| Migrations | Liquibase | + ClickHouse 0.7.2 |
| Caching | Redisson (Redis) | 3.50.0 |
| Observability | OpenTelemetry | 2.18.0 |
| Code Style | Spotless | 2.46.0 |

### Frontend (TypeScript/React)
| Component | Technology | Version |
|-----------|------------|---------|
| Language | TypeScript | 5.4.5 |
| Framework | React | 18.3.1 |
| Build | Vite | 5.2.11 |
| Styling | Tailwind CSS | 3.4.3 |
| State | Zustand | 4.5.2 |
| Routing | TanStack Router | 1.36.3 |
| Data Fetching | TanStack Query | 5.45.0 |
| UI Components | Radix UI | - |
| Testing | Vitest, Playwright | 3.0.5, 1.45.3 |

### Python SDK
| Component | Technology |
|-----------|------------|
| Language | Python 3.8+ |
| HTTP Client | httpx |
| Validation | Pydantic 2.x |
| Testing | pytest |
| CLI | Click |

### TypeScript SDK
| Component | Technology | Version |
|-----------|------------|---------|
| Language | TypeScript | 5.9.3 |
| Runtime | Node.js | 18+ |
| Build | tsup | 8.3.6 |
| HTTP | node-fetch | 3.3.2 |
| Validation | Zod | 3.25.55 |

## Dependency Management

### Principles
1. **Pin major versions** for production stability
2. **Use BOMs** (Java) for consistent transitive dependencies
3. **Semantic versioning**: `^` for minor, `~` for patch updates
4. **Dependabot** for automated security updates
5. **Manual review** required for major version bumps

### Version Bounds by Language
- **Java**: Use `${version}` properties in pom.xml
- **Python**: `>=2.0.0,<3.0.0` style bounds in pyproject.toml
- **Node.js**: `^` prefix in package.json, specify `engines.node`

### Compatibility Requirements
- Java 21 required for all backend services
- Python 3.8-3.14 for main SDK, 3.9-3.12 for optimizer
- Node.js 18+ for TypeScript SDK


## Build Commands

| Component | Build | Test | Lint |
|-----------|-------|------|------|
| Backend | `mvn package -DskipTests` | `mvn test` | `mvn spotless:apply` |
| Frontend | `npm run build` | `npm test` | `npm run lint` |
| Python SDK | `python -m build` | `pytest` | `ruff check` |
| TS SDK | `npm run build` | `npm test` | `npm run lint` |

## Detailed Rules

Component-specific guidelines are in `.cursor/rules/`:
- Backend: `.cursor/rules/apps/opik-backend/`
- Frontend: `.cursor/rules/apps/opik-frontend/`
- Python SDK: `.cursor/rules/sdks/python/`
- TypeScript SDK: `.cursor/rules/sdks/typescript/`
- Optimizer: `.cursor/rules/sdks/opik_optimizer/`
