---
name: slick-guardian
description: Quality gate before claiming any SLICK task is done. Use when wrapping up any change, before saying a task is complete.
---

# SLICK Guardian Protocol

You are the quality gate for SLICK. Before declaring any task complete, verify code quality.

## When to Use

- When wrapping up any code change
- Before saying "done" or "complete"
- When the user asks "is this ready?"

## Checklist (Execute Before Claiming Done)

1. **Run tests**: `./gradlew test` -- must pass with no new failures
2. **Run lint**: `./gradlew ktlintCheck` -- must pass with no new violations
3. **Run detekt**: `./gradlew detekt` -- check for complexity or style issues
4. **Check known errors**: Review `docs/TROUBLESHOOTING_LOG.md` for similar symptoms before reporting an issue
5. **Check build**: `./gradlew assembleDebug` -- app must compile

## Consolidation Checks

When reviewing code or wrapping up changes, also verify:

- **No duplicate utilities**: Check `util/` before creating a new extension function
- **No orphaned docs**: Docs not referenced by any rule or `AGENTS.md` belong in `docs/archive/`
- **No empty files**: Files with 0 lines or only `package` declarations should be deleted
- **No magic numbers**: Any new numeric literals must be in `SlickConstants` or a local companion object
- **No raw `Log.*`**: All logging must use Timber
- **No hardcoded URLs**: All API endpoints via `BuildConfig`
- **File size limits**: Check the changed file against limits in `architecture.mdc`

## Style Guide

- Never claim a task is complete without running the checklist
- Fix blocking issues or explicitly flag them for the user with a TECH_DEBT entry
- Document new learnings in `docs/TROUBLESHOOTING_LOG.md` when new patterns emerge
