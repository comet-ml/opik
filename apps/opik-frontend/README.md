# React Comet Opik

This is a frontend part of Comet Opik project

## Getting Started

### Install

Access the project directory.

```bash
cd apps/opik-frontend
```

In order to run the frontend, you will need to have node available locally. For
this recommend installing [nvm](https://github.com/nvm-sh/nvm). For this guide
we will assume you have nvm installed locally:

```bash
# Use version 20.15.0 of node
nvm use lts/iron

npm install
```

Start Develop serve with hot reload at <http://localhost:5173>.
The dev server is set up to work with Opik BE run on http://localhost:8080. All requests that tarts with `/api` prefix is proxying to it.
The server port can be changed in `vite.config.ts` file section `proxy`.

```bash
npm start
```

### Lint

```bash
npm run lint
```

### Typecheck

```bash
npm run typecheck
```

### Build

```bash
npm run build
```

### Test

```bash
npm run test
```

View and interact with your tests via UI.

```bash
npm run test:ui
```

## Comet Integration

In order to run the frontend locally with the Comet integration we have to run the frontend in `comet` mode, but first, we should override the environment variables

1. Create a new `.env.comet.local` file with this content:

```
VITE_BASE_URL=/opik/
VITE_BASE_API_URL=/opik/api
VITE_BASE_COMET_URL=https://staging.dev.comet.com/
VITE_BASE_COMET_API_URL=https://staging.dev.comet.com/api
```

2. Now you can start the frontend in `comet` mode:

```bash
npm start -- --mode=comet
```
