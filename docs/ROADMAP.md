# Roadmap And Known Gaps

## P0: Correctness And Security

- Add message-scoped Diff retrieval for OpenCode 1.18.25.
- Add ViewModel tests for stale connection generations, epochs and SSE snapshot barriers.
- Add fixture tests for Desktop bridge projection and migration.
- Add a Windows Job Object or equivalent so hard-killing the launcher cannot leave orphaned children. Normal lifecycle already has readiness checks and cleanup.
- Decide and implement secure production transport before any WAN exposure.

## P1: Protocol Completeness

- Add paginated global session loading beyond the first 250 roots.
- Move selected-session detail toward current `/api/session/*` projected messages and durable event cursor.
- Define current-protocol Todo/Diff strategy once upstream contracts stabilize.
- Add endpoint capability cache while keeping endpoint-specific fallback.
- Handle content-type mismatch explicitly when Web fallback returns HTML.

## P1: Product Reliability

- Foreground service for intentional long-running monitoring.
- Optional relay/push architecture for reliable background alerts.
- Connection diagnostics screen showing server, bridge, reverse/LAN state without secrets.
- Better offline/retry UX and explicit last-updated timestamps.
- Automated bridge token rotation/reprovision workflow.

## P1: Distribution

- Release signing configuration outside Git.
- Reproducible signed release process.
- Upgrade/update channel and rollback plan.
- Privacy/security disclosure for LAN and browser auth behavior.

## P2: UI And Accessibility

- More complete Markdown: tables, task lists, nested lists, code-block actions and images.
- Large-screen/tablet layouts.
- Compose accessibility audit and UI tests.
- Better Diff context and per-file navigation.
- More complete tool cards and attachment upload flows.

## P2: Operations

- Automated cold OpenCode database backup/restore with integrity verification.
- Explicit wireless ADB disable helper.
- Firewall rule reconciliation instead of name-only existence checks.
- VPN address discovery or manual bridge endpoint workflow.
- Add durable redacted logs for hidden bridge startup; health/readiness checks already exist.

## Deferred By Design

- Scraping private sidecar credentials
- Storing a second local session database
- Treating Desktop files as live state
- Raw public-Internet cleartext exposure
- Migrating an Agent while it is running
