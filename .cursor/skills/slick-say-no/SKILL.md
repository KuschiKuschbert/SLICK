---
name: slick-say-no
description: Challenges scope and complexity before adding to SLICK. Use when the user requests a new feature or significant change, to ensure we prefer reuse over creation.
---

# SLICK Say-No Protocol

Challenge scope before adding. Reuse over creation.

## When to Use

- User requests a new feature
- User requests a significant change that would cross module boundaries
- Scope seems to be growing beyond the current phase

## Decision Tree

1. **Clarify the goal**: What is the user actually trying to achieve?

2. **Check reuse**: Can an existing component be extended instead of creating new?
   - Existing engine managers in `engine/*/`
   - Existing composables in `ui/components/`
   - Existing utilities in `util/`
   - Existing patterns in similar features

3. **Check the phase**: Is this feature in the plan's current phase? If not, add to backlog.

4. **Prefer extending**: Recommend extending before creating

5. **If creating**: Suggest phased approach if scope is large. One component at a time.

## SLICK-Specific Scope Guards

- **No dashcam**: CameraX encoding is explicitly banned by the Musk Protocol
- **No external hardware requirements**: Everything must work on the phone alone
- **No imperial units**: Any feature request involving non-metric units -- decline and convert
- **No continuous wake word**: Battery drain from always-on mic is explicitly banned

## Style Guide

Be helpful, not obstructive. Guide toward simpler solutions. "Can we achieve this with what we already have?" is always the first question.
