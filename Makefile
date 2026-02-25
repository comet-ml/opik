.PHONY: cursor codex claude clean-agents help hooks hooks-remove precommit-sdks precommit-sdks-all

AI_DIR := .agents
CURSOR_DIR := .cursor
CLAUDE_DIR := .claude
HOOKS_SRC := .hooks
HOOKS_DEST := .git/hooks
SDK_DIFF_BASE ?= origin/main

define link_agent_config
	@if [ ! -d "$(AI_DIR)" ]; then \
		echo "Error: $(AI_DIR)/ does not exist. Run the migration first."; \
		exit 1; \
	fi
	@if [ -e "$(1)" ] && [ ! -L "$(1)" ]; then \
		echo "Error: $(1) exists and is not a symlink."; \
		echo "Remove it manually if you want to use .agents/ as source."; \
		exit 1; \
	fi
	@if [ -L "$(1)" ]; then \
		echo "$(1) symlink already exists"; \
	else \
		echo "Creating symlink $(1) -> $(AI_DIR)..."; \
		ln -s "$(AI_DIR)" "$(1)"; \
	fi
endef

define clean_synced_files
	@if [ -d "$(AI_DIR)/$(1)" ]; then \
		find "$(AI_DIR)/$(1)" $(2) 2>/dev/null | while read src; do \
			rel=$${src#$(AI_DIR)/$(1)/}; \
			dest_rel=$$rel; \
			$(3) \
			dest="$(CLAUDE_DIR)/$(4)/$$dest_rel"; \
			[ -f "$$dest" ] && rm -f "$$dest" && echo "  Removed $$dest" || true; \
		done; \
	fi
endef

help:
	@echo "AI Editor Configuration Sync"
	@echo ""
	@echo "  make cursor        - Ensure .cursor symlink points to .agents/"
	@echo "  make codex         - Ensure .codex symlink + generate Codex AGENTS.override.md from .agents/rules/*.mdc"
	@echo "  make claude        - Sync .agents/ to .claude/ + generate .mcp.json (preserves local files)"
	@echo "  make clean-agents  - Remove synced files from .claude/ and local Codex artifacts"
	@echo ""
	@echo "Git Hooks"
	@echo ""
	@echo "  make hooks         - Install pre-commit hooks"
	@echo "  make hooks-remove  - Remove pre-commit hooks"
	@echo ""
	@echo "SDK Checks"
	@echo "  make precommit-sdks       - Run staged/prepared file checks for all SDKs"
	@echo "  make precommit-sdks-all   - Run all-file pre-commit checks for all SDKs"
	@echo ""

# Sync to Cursor (symlink .cursor -> .agents)
cursor:
	$(call link_agent_config,$(CURSOR_DIR))
	@echo "Cursor ready!"

codex:
	$(call link_agent_config,.codex)
	@./scripts/sync-codex.sh "$(AI_DIR)" "AGENTS.md" "AGENTS.override.md"
	@echo "Codex ready! Generated AGENTS.override.md and .agents/generated/codex/rules/*.md"

# Sync to Claude (preserve nested structure, convert frontmatter)
# Converts: .mdc -> .md, Cursor frontmatter -> Claude frontmatter
# Cursor: globs/alwaysApply -> Claude: paths
# Preserves local customizations (files in .claude/ not in .agents/)
claude:
	@if [ ! -d "$(AI_DIR)/rules" ]; then \
		echo "Error: $(AI_DIR)/rules/ does not exist."; \
		exit 1; \
	fi
	@echo "Syncing $(AI_DIR)/rules/ to $(CLAUDE_DIR)/rules/ (preserving local files)..."
	@mkdir -p $(CLAUDE_DIR)/rules
	@find $(AI_DIR)/rules -name "*.mdc" | while read src; do \
		rel=$${src#$(AI_DIR)/rules/}; \
		dest="$(CLAUDE_DIR)/rules/$${rel%.mdc}.md"; \
		mkdir -p "$$(dirname "$$dest")"; \
		./scripts/convert-frontmatter.sh cursor-to-claude "$$src" "$$dest"; \
		echo "  $$rel -> $${rel%.mdc}.md"; \
	done
	@# Sync commands (preserve local files)
	@if [ -d "$(AI_DIR)/commands" ]; then \
		echo "Syncing $(AI_DIR)/commands/ to $(CLAUDE_DIR)/commands/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/commands; \
		find $(AI_DIR)/commands -name "*.md" | while read src; do \
			rel=$${src#$(AI_DIR)/commands/}; \
			dest="$(CLAUDE_DIR)/commands/$$rel"; \
			mkdir -p "$$(dirname "$$dest")"; \
			cp "$$src" "$$dest"; \
			echo "  $$rel"; \
		done; \
	fi
	@# Sync skills (preserve local files)
	@if [ -d "$(AI_DIR)/skills" ]; then \
		echo "Syncing $(AI_DIR)/skills/ to $(CLAUDE_DIR)/skills/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/skills; \
		cp -r $(AI_DIR)/skills/* $(CLAUDE_DIR)/skills/; \
		echo "  skills synced"; \
	fi
	@# Sync agents (preserve local files)
	@if [ -d "$(AI_DIR)/agents" ]; then \
		echo "Syncing $(AI_DIR)/agents/ to $(CLAUDE_DIR)/agents/ (preserving local files)..."; \
		mkdir -p $(CLAUDE_DIR)/agents; \
		cp -r $(AI_DIR)/agents/* $(CLAUDE_DIR)/agents/; \
		echo "  agents synced"; \
	fi
	@# Convert MCP config to Claude CLI format (.mcp.json at repo root)
	@if [ -f "$(AI_DIR)/mcp.json" ]; then \
		echo "Converting MCP config to Claude CLI format..."; \
		./scripts/convert-mcp.sh "$(AI_DIR)/mcp.json" ".mcp.json"; \
	fi
	@echo "Claude ready! Files in $(CLAUDE_DIR)/ and .mcp.json"

# Clean generated files (preserves .agents/ and local customizations in .claude/)
# Only deletes files that have a corresponding source in .agents/
clean-agents:
	@echo "Removing generated files (preserving local customizations)..."
	@[ -L "$(CURSOR_DIR)" ] && rm -f $(CURSOR_DIR) && echo "Removed $(CURSOR_DIR) symlink" || true
	@[ -L ".codex" ] && rm -f .codex && echo "Removed .codex symlink" || true
	@[ -f "AGENTS.override.md" ] && rm -f AGENTS.override.md && echo "Removed AGENTS.override.md" || true
	@[ -d "$(AI_DIR)/generated/codex" ] && rm -rf "$(AI_DIR)/generated/codex" && echo "Removed $(AI_DIR)/generated/codex" || true
	@# Clean synced files from Claude surfaces using shared deletion helper.
	$(call clean_synced_files,rules,-name "*.mdc",dest_rel=$${dest_rel%.mdc}.md;,rules)
	$(call clean_synced_files,commands,-name "*.md",:;,commands)
	$(call clean_synced_files,skills,-type f,:;,skills)
	$(call clean_synced_files,agents,-name "*.md",:;,agents)
	@# Clean up empty directories
	@find $(CLAUDE_DIR) -type d -empty -delete 2>/dev/null || true
	@[ -f ".mcp.json" ] && rm -f .mcp.json && echo "Removed .mcp.json" || true
	@echo "Done! Local customizations preserved."

# Install Git hooks from .hooks/ to .git/hooks/
hooks:
	@if [ ! -d "$(HOOKS_SRC)" ]; then \
		echo "Error: $(HOOKS_SRC)/ does not exist."; \
		exit 1; \
	fi
	@if [ ! -d "$(HOOKS_DEST)" ]; then \
		echo "Error: $(HOOKS_DEST)/ does not exist. Is this a git repository?"; \
		exit 1; \
	fi
	@cp $(HOOKS_SRC)/pre-commit $(HOOKS_DEST)/pre-commit
	@chmod +x $(HOOKS_DEST)/pre-commit
	@echo "Pre-commit hook installed."

# Remove Git hooks
hooks-remove:
	@if [ -f "$(HOOKS_DEST)/pre-commit" ]; then \
		rm -f $(HOOKS_DEST)/pre-commit; \
		echo "Pre-commit hook removed."; \
	else \
		echo "No pre-commit hook found."; \
	fi

# Run SDK pre-commit style checks (changed files only / explicit script checks)
precommit-sdks:
	@echo "Running SDK-level pre-commit checks on changed files..."
	@./scripts/run-precommit-changed-files.sh \
		--config sdks/python/.pre-commit-config.yaml \
		--pathspec sdks/python/ \
		--base-ref "$(SDK_DIFF_BASE)" \
		--label "Python SDK files"
	$(MAKE) -C sdks/opik_optimizer precommit SDK_DIFF_BASE="$(SDK_DIFF_BASE)"
	@ts_files=$$(./scripts/run-precommit-changed-files.sh \
		--pathspec sdks/typescript/ \
		--base-ref "$(SDK_DIFF_BASE)" \
		--label "TypeScript SDK files" \
		--print-files | grep -E '^sdks/typescript/.*\.(ts|tsx|js|jsx)$$' || true); \
	if [ -n "$$ts_files" ]; then \
		echo "TypeScript SDK source files changed. Running lint and typecheck..."; \
		cd sdks/typescript && npm run lint && npm run typecheck; \
	else \
		echo "No TypeScript SDK source files changed. Skipping lint and typecheck."; \
	fi

# Run full all-file SDK checks
precommit-sdks-all:
	@echo "Running all-file SDK checks..."
	@cd sdks/python && pre-commit run --all-files -c .pre-commit-config.yaml
	@cd sdks/opik_optimizer && pre-commit run --all-files -c .pre-commit-config.yaml
	@cd sdks/typescript && npm run lint && npm run typecheck
