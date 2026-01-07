# Contributing to ESNODE-Core

Welcome! This project is source-available under the ESNODE BUSL-1.1 license with additional commercial and trademark restrictions. Please read this guide before opening issues or sending patches.

## Ground rules
- Respect the license in `LICENSE`: internal use and learning are allowed; selling/hosting/rebranding/redistribution of binaries or forks is prohibited. Trademark use is governed by `docs/TRADEMARK_POLICY.md`.
- All contributors must agree to the Contributor License Agreement in `docs/CLA.md`. By submitting a PR, you affirm the CLA terms.
- We reserve the right to decline contributions that conflict with roadmap, security posture, or license constraints.
- Do not remove or alter license headers or trademark notices.

## How to contribute
1) Discuss first: open a GitHub issue to propose features/bug fixes; include motivation, scope, and acceptance criteria.  
2) Fork and branch: create a feature branch from the latest `main`.  
3) Keep changes scoped: one logical change per PR; avoid mixing refactors with feature work.  
4) Tests: add/extend tests for new behavior; run `cargo test --workspace --locked` before sending a PR.  
5) Style: follow existing Rust formatting (`cargo fmt`) and lint behavior (`cargo clippy` if enabled). Prefer UUID-first labels for GPU/MIG metrics per current design.  
6) Security: do not commit secrets; report vulnerabilities privately to security@estimatedstocks.com.  
7) Licensing: files must retain the BUSL notice and copyright header where present.

## Code of Conduct
- Be respectful, constructive, and inclusive in issues and reviews.
- Focus discussions on technical merit; no harassment, discrimination, or personal attacks.
- Report CoC concerns to conduct@estimatedstocks.com.

## Release & tagging
- Official releases are cut by maintainers. Tagging `vX.Y.Z` triggers CI to build and publish binaries (see `.github/workflows/release.yml`). External contributors should not tag releases.
- To test packaging locally, run `scripts/dist/esnode-core-release.sh` (optionally set `ESNODE_VERSION=X.Y.Z`).

## Pull request checklist
- [ ] Issue linked or clear rationale provided.
- [ ] Tests updated/added and passing (`cargo test --workspace --locked`).
- [ ] Docs updated if behavior or config changes (`README.md`, `docs/quickstart.md`, `docs/metrics-list.md`, etc.).
- [ ] No new secrets or proprietary assets added.
- [ ] CLA accepted by submitting the PR.

Thank you for helping improve ESNODE-Core!
