# Running Video Tests on Localhost

## The Problem

Tests might run on **staging** instead of **localhost** because the `.env` file in `typescript-tests/` contains staging configuration.

## The Solution

Create a `.env.local` file that overrides `.env` for local development.

---

## Step-by-Step Setup for Local Testing

### 1. Check Current Configuration

```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# See what's in your .env file
cat .env | grep OPIK_BASE_URL

# You probably see:
# OPIK_BASE_URL=https://staging-something.opik.com
```

### 2. Create `.env.local` File

```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# Create .env.local (this overrides .env)
cat > .env.local << 'EOF'
# Local Development Configuration
# This file overrides .env when running tests locally

OPIK_BASE_URL=http://localhost:5173
TOGETHERAI_API_KEY=tgp_v1_UiVDmeBZ_yMHOoefOLfRQlL9BlGh7fxRhE48OYnBQh4

# Local doesn't need authentication
# Leave these empty or commented out
EOF
```

### 3. Verify playwright.config.ts Loads .env.local

The config has been updated to load `.env.local` first:

```typescript
// playwright.config.ts lines 5-7
dotenv.config({ path: '.env.local' });  // Loads .env.local (local override)
dotenv.config();                         // Loads .env (staging/CI default)
```

This means:
- ✅ `.env.local` values take priority
- ✅ `.env` is still loaded for any missing variables
- ✅ CI/CD uses `.env` (no `.env.local` in CI)
- ✅ Local developers use `.env.local`

### 4. Run Tests on Localhost

```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# Now tests will run on localhost!
npm run test:videosupport

# Debug mode on localhost
npm run test:debug -- tests/video-support/

# UI mode on localhost
npm run test:ui -- tests/video-support/
```

---

## Verify It's Working

When you run tests, you should see in the console:

**BEFORE (wrong - staging)**:
```
[chromium] › tests/video-support/playground-video.spec.ts:...
Navigating to https://staging-something.opik.com/default/playground
```

**AFTER (correct - localhost)**:
```
[chromium] › tests/video-support/playground-video.spec.ts:...
Navigating to http://localhost:5173/default/playground
```

---

## Alternative: Temporarily Edit .env Directly

If you don't want to create `.env.local`, you can temporarily edit `.env`:

```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# Backup current .env
cp .env .env.staging.backup

# Edit .env
nano .env  # or code .env or vim .env

# Change OPIK_BASE_URL to:
OPIK_BASE_URL=http://localhost:5173
```

**But remember**: `.env` is gitignored, so your changes won't be committed. Use `.env.local` for permanent local config!

---

## File Priority

The updated configuration loads files in this order:

1. **`.env.local`** - Local development (highest priority) ⭐
2. **`.env`** - Default/staging configuration
3. **Environment variables** - Override everything (for CI/CD)

---

## What Should Be in Each File

### `.env` (Staging/CI - DO NOT EDIT for local testing)
```bash
# This file is for staging/CI
OPIK_BASE_URL=https://staging.opik.com
OPIK_TEST_USER_EMAIL=test@example.com
OPIK_TEST_USER_NAME=testuser
OPIK_TEST_USER_PASSWORD=secretpassword
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
```

### `.env.local` (Local development - CREATE THIS) ⭐
```bash
# This file is for YOUR local machine
OPIK_BASE_URL=http://localhost:5173
TOGETHERAI_API_KEY=tgp_v1_UiVDmeBZ_yMHOoefOLfRQlL9BlGh7fxRhE48OYnBQh4

# Optional: Add other providers if you want to test them locally
# OPENAI_API_KEY=sk-...
# ANTHROPIC_API_KEY=sk-ant-...
```

---

## Quick Commands

```bash
# 1. Navigate to test directory
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests

# 2. Create .env.local
echo 'OPIK_BASE_URL=http://localhost:5173' > .env.local
echo 'TOGETHERAI_API_KEY=tgp_v1_UiVDmeBZ_yMHOoefOLfRQlL9BlGh7fxRhE48OYnBQh4' >> .env.local

# 3. Run tests
npm run test:videosupport

# That's it!
```

---

## Why I Didn't Encounter This

I used **Playwright MCP browser tools** which bypass the test configuration entirely and connect directly to the URL I specify (`http://localhost:5173`). 

But the actual **test suite** reads from `.env`, so you need to configure it properly!

---

**TL;DR**: Create `.env.local` with `OPIK_BASE_URL=http://localhost:5173` and tests will run on localhost! 🎯





