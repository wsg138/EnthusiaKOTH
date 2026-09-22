# EnthusiaKOTH testing guide

Handwritten KOTH regression tests live in this repository. Sentinel Sim is an additional built-plugin/runtime compatibility layer; it does not replace these unit/integration tests.

## Test-hardening additions

### `PaymentRecoveryPolicyTest`
Protects crash/startup economy recovery. This is intentionally conservative because duplicating a refund is a money-integrity defect.

The tests require:

- `REFUND_PENDING` is the only status eligible for automatic refund;
- `PREPARED`, `CHARGED`, and `REFUNDING` require manual reconciliation because the external economy result can be ambiguous;
- terminal `REFUNDED`, `SETTLED`, and `CANCELLED` entries are ignored;
- every enum status is explicitly represented by the policy test so a future status cannot silently inherit unsafe recovery behavior;
- `recover(...)` dispatches only the expected callbacks.

### `FullFeatureCoverageContractTest`
Inventory guard for major production surfaces. It requires concrete regression evidence for capture/takeover, event modes, queueing, paid starts/recovery, start/team authorization, flares, scheduling/timezones, moving objectives, restrictions/region protection, persistence/recovery, migration, configuration/permissions, Discord/placeholders, and plugin compatibility.

This contract does **not** prove correctness because a filename exists. New or changed behavior needs real assertions first; then update the inventory if the feature/evidence map changes.

## Running tests

Use the checked-in Gradle wrapper and the repository's configured Java/Kotlin toolchain.

Full validation:

```bash
./gradlew clean test build
```

Windows:

```powershell
.\gradlew.bat clean test build
```

Focused recovery-policy test:

```bash
./gradlew test --tests 'net.badgersmc.ek.application.PaymentRecoveryPolicyTest'
```

Coverage-inventory guard:

```bash
./gradlew test --tests 'net.badgersmc.ek.FullFeatureCoverageContractTest'
```

## Result locations

Gradle HTML report:

- `build/reports/tests/test/index.html`

JUnit XML:

- `build/test-results/test/*.xml`

GitHub Actions logs are the authoritative hosted exact-head result when reviewing a PR. Always tie a claimed pass to the exact PR head SHA.

## Failure triage

- **Payment-recovery assertion:** treat as a money-safety regression until proven otherwise. Never broaden automatic refund eligibility merely to make the test pass.
- **Coverage inventory:** locate the real replacement behavioral test or add missing regression coverage. Do not point it at an unrelated test.
- **Compile/build/harness:** repair dependency/tooling/test code without changing production semantics just for CI.
- **Sentinel lifecycle:** separate from repository tests; use Sentinel evidence when a built plugin cannot load/enable/disable or simulated runtime behavior fails.
- **Hosted job with zero steps/no runner:** infrastructure failure, not a test pass or product failure.

## Adding or changing KOTH behavior

Review applicable success and failure paths, including:

- permissions/start source and event lock state;
- queue ordering and duplicate activation;
- payment charge/refund/journal transitions;
- crash/restart ambiguity and idempotency;
- team/guild versus solo behavior;
- capture leave/takeover behavior;
- scheduled versus manual starts and timezone boundaries;
- restrictions/protection cancellation;
- persistent operational state and migrations;
- provider-missing behavior (Vault, Discord, guilds, placeholders) when applicable.

Use deterministic unit tests for policy/state machines and persistence. Use Sentinel for built-plugin/runtime checks. Use real Paper when the behavior depends on server internals or physics that a test double cannot model faithfully.

## Security/privacy

Never place economy credentials, Discord webhooks, production databases, player records, IPs, tokens, or production config secrets in test fixtures. Use generated UUIDs, temporary files and fakes.

## Worker coordination

Reconcile live `main`, open PRs and changed paths before editing. This hardening branch is test/documentation-only. A product defect found by these tests should be fixed in its correct owning branch/PR rather than mixed into unrelated test-hardening work.
