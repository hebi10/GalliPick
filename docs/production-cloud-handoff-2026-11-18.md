# GalliPick production readiness — development handoff (2026-11-18)

Status: **PREPARATION ONLY**. This branch creates Android environment selection and isolated subscription-benefit contracts. It does not deploy Firebase, activate Play products, sell subscriptions, or permit production Storage operations.

## Confirmed baseline
- `main` at `dad9e11d360892e427050e43623f94c5d25914e0` — GalliPick `0.2.16 (27)`, development-only Cloud pilot.
- The existing `play-bundle.yml` still builds the **development Cloud pilot** only, with manual workflow dispatch from verified `main`. Do not quietly retarget it to production.
- `firebase/functions/pilot.js` and `cloud-billing.js` still reject `gallipick-production` and require developer tester UIDs/testPurchase. `firebase/functions/index.js` still specifies a development-only service account/RTDN topic. **No production Cloud backend is live through this branch.**
- `firebase/functions/backup.js` enforces a 64 MiB per-original pilot cap; `cloud-billing.js` enforces a 1 GiB pilot quota. Those limits are **not** an approved production price/capacity contract.
- PR [#129](https://github.com/hebi10/GalliPick/pull/129) handles Files UI and must be verified and merged independently. Do not merge this branch just to bypass its Android device tests.

## Implemented preparatory code
- Android `-PgallipickCloudEnvironment=development|production` is fail-closed. The default stays `development`; `production` is valid only with `-PgallipickPlayCloudPilot=true` and a *matching, privately injected*, Play SHA-1-validated `app/src/release/google-services.json`. No real keys are included.
- `cloudRuntimeFor(projectId)` pins the Firebase Functions endpoint and accepted private Storage bucket per exact project. Unknown projects are disabled instead of using the development endpoint.
- Client entitlement state can distinguish server-verified `gallipick_ad_free` benefits from active Cloud storage. An ad-free-only subscriber has **zero** Cloud quota; any active paid Cloud plan includes ad removal. Both use server-verified expiry, never device settings as proof.
- A server-side, independently testable benefit calculator validates exact product/base-plan contracts, active expiry/verification timestamps, and approved bounded Cloud quota. It is **not wired to production purchase processing** because approved production catalogue and live Play service-account permissions do not exist yet.
- Cloud UI shows a verified standalone ad-free subscription without portraying it as a storage plan and suppresses a duplicate purchase CTA. Changing standalone to Cloud requires a separate Play billing replacement flow to avoid overlapping charges.

## Blockers that cannot be completed by code-only changes
1. **Play Console**: `gallipick_ad_free` product object does not imply a purchasable offer. Confirm the `monthly` / `annual` base plans are actually saved and ACTIVE, priced per approved market, and visible to a license tester. Separate console owner/session required.
2. **Play Developer API**: confirm terms, enable Google Play Android Developer API for the production project, grant the runtime service account the minimum necessary per-app order/subscription lookup and management access. Test read-only `subscriptionsv2.get` with a license-test token.
3. **Google OAuth**: `com.hebi.gallipick` and Play-signing SHA-1 already referenced in the development project. Check duplicate Android OAuth client constraints and choose a migration/ownership arrangement before configuring the production `google-services.json`. Verify Google sign-in on a Play-signed build. Do not swap the development OAuth client without considering existing tester login.
4. **Blaze and costs**: linking a billing account to `gallipick-production` changes cost exposure. Explicit approval; configure budgets/alerts, conservative Function concurrency/instance quotas, storage/egress policy, abuse controls. Budget alerts do not hard-stop spending.
5. **Production Cloud products and data**: decide actual capacity tiers (100 GB and 1 TB are candidates only), recurring price, large media/chunked upload protocol, limits, migration and expiration/retention/download/deletion-notice policy. Never turn pilot limits off or delete originals based on an unstated expiry.
6. **Runtime server**: make a separate production implementation for Functions service account, Storage/Firestore, entitlement verification, real purchases (no `testPurchase` restriction), RTDN Pub/Sub topic, idempotent reconciliation, token ownership and linked purchases. Server-side SKU verification must stay fail-closed until operational approval.
7. **Store/release**: Google Play Data safety, app access, privacy, ad status and account/data-deletion policy are reviewed separately. Only after all code/QA gates complete and explicit approval: signed bundle from reviewed `main`, internal test, actual purchase/refund/recovery tests, and later tracks on separate authorizations.

## Required tests before production enablement
- Android debug uses `gallipick-dev` even when no release config is present; unknown and mismatched production Firebase configurations fail instead of falling back.
- Android JVM tests: Cloud-only vs ad-free-only benefit boundaries, current UID/expiry, monthly/annual recurring offer identifiers.
- Firebase Node tests: paid Cloud => ad-free; ad-free-only => 0 storage; expired, out-of-catalog, future-checked and malformed records => no benefits.
- Manual: Firebase OAuth sign-in across Play signing, two different Google accounts, network offline, account changes; prod/dev cannot query the other bucket; Play license tester purchase/change/recovery/cancellation; no default live purchase or storage behavior.
- Device/API 29/35/36 plus latest UI PR QA; Play upload is separate from any successful CI.

Links:
- GitHub issue: https://github.com/hebi10/GalliPick/issues/128
- Google Drive plan: https://docs.google.com/document/d/1nm-znFD9MXNbmYSKqruEA2VkT-PMZusoL1VoevrD-8w/edit
