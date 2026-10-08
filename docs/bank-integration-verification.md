# KOTH guild-bank contract repair

## Spec

WHEN KOTH credits a guild reward or debits guild funds THE SYSTEM SHALL use
LumaGuilds' system guild-bank API without creating a personal wallet actor.
IF the provider or system API is unavailable, rejects the operation, or the
amount is non-finite, non-positive, fractional or outside the Int-bounded bank
range THEN THE SYSTEM SHALL return failure without invoking personal-bank APIs.

Task BANK-001: repair only the LumaGuilds infrastructure adapter and its excluded
compile mirror; preserve payment journals, rewards, settings and permissions.
Base: fetched wsg138 main f80adebb10f5be991abe20de41e505ebceb3d5a5.
Real network companion: BadgersMC/LumaGuilds a15b244e8a294bf18e6dedf722462edf9faa40ae.
GuildLookupImpl delegates actor bankDeposit/Withdraw to personal banking, whereas
systemBankDeposit/Withdraw credit/debit guild funds directly with bank safeguards.

SPEAR: spec -> adapter regression proof -> smallest engine repair -> verify
excluded compile mirror/real companion descriptors -> refine full tests/build.
No repository EARS/state helper exists; this is the manual requirement/task/state
record. Local mock-based tests prove dispatch/failure paths, not actual money
movement in a live server. Hosted review, merge, monorepo pin and live acceptance
remain separate. No production access or deployment.

## Verification

- Red: seven new adapter tests fail against the old personal-bank dispatch.
  The compile mirror was extended only with the real provider's public system
  methods to make this adapter test compile; runtime dispatch was still old.
- Green: nine adapter tests pass; Java 21 canonical clean test/shadowJar passes
  167 tests, zero failures/errors/skips. Financial amounts remain exact integral
  units within the real provider's Int bound. Missing/old API returns failure;
  unexpected provider exceptions propagate without retry. No fallback can touch
  a player's wallet. Existing reward application catches/report failures.
- Architecture: only infrastructure dispatch and excluded compile mirror change.
  Real pinned GuildLookup/GuildLookupImpl signatures and direct system bank
  delegation were inspected; this does not claim a live bank transaction.
- Local unmerged shaded artifact SHA-256:
  `4b0e24653fc47ae84a23127351c504f75c207b3ef5aab69e5fd7dfecddf71ea4`.
- Hosted exact-head checks/review, upstream merge and network pin still pending.
