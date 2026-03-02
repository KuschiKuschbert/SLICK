# SLICK Tech Debt

Tracked issues that need attention but couldn't be safely fixed in the current session.
Never leave "I noticed but didn't fix" undocumented -- add it here.

Format: `| File | Issue | Why Not Auto-Fixed | Effort | Priority |`

| File | Issue | Why Not Auto-Fixed | Effort | Priority |
|------|-------|-------------------|--------|----------|
| _None yet_ | | | | |

---

## How to Use This File

**Adding an item:**
When you notice an issue but can't safely fix it in the current task, add a row:

```markdown
| engine/mesh/ConvoyMeshManager.kt | Object pool not yet implemented for RiderTelemetry | Would require significant refactor beyond current scope | Medium | P2 |
| ui/inflight/Zone2Map.kt | MapLibre camera anchoring uses hardcoded 0.55f | Should be screen-density aware | Small | P3 |
```

**Priority levels:**
- P1: Blocking -- must fix before next phase
- P2: High -- fix within current phase
- P3: Medium -- fix in next phase
- P4: Low -- fix when touching the file anyway

**Effort levels:** XS / Small / Medium / Large / XL

**Clearing items:** Remove rows when debt is paid. Add a note to `docs/brain/MEMORY.md` if a pattern was learned.
