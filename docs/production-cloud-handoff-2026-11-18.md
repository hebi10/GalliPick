# GalliPick production readiness — development handoff (2026-11-18)

Status: **AD-REMOVAL IMPLEMENTATION FOR REVIEW**. The branch supports Android monthly and annual ad-removal checkout, server purchase verification, and an explicit production signed-bundle option. Production Cloud backup is disabled.

## Confirmed baseline
- `main` at `dad9e11d360892e427050e43623f94c5d25914e0` — GalliPick `0.2.16 (27)`, development-only Cloud pilot.
- The currently merged `main` still signs development pilot bundles. This branch adds explicit production selection and verifies the Firebase project in the signed bundle before internal publishing. Select `cloud_production` with `GALLIPICK_PLAY_PRODUCTION_CLOUD_CONFIG_BASE64` and the Play app-signing SHA-1 after the branch is merged and its checks pass.
- The production ad-removal Functions use their own runtime service account, Firestore collections, and RTDN topic. The development pilot retains its existing credentials and topic. Backup calls remain gated in production.
- `firebase/functions/backup.js` enforces a 64 MiB per-original pilot cap; `cloud-billing.js` enforces a 1 GiB pilot quota. Those limits are **not** an approved production price/capacity contract.
- PR [#129](https://github.com/hebi10/GalliPick/pull/129) handles Files UI and must be verified and merged independently. Do not merge this branch just to bypass its Android device tests.

## Implemented preparatory code
- Android `-PgallipickCloudEnvironment=development|production` is fail-closed. The default stays `development`; `production` is valid only with `-PgallipickPlayCloudPilot=true` and a *matching, privately injected*, Play SHA-1-validated `app/src/release/google-services.json`. No real keys are included.
- `cloudRuntimeFor(projectId)` pins the Firebase Functions endpoint and accepted private Storage bucket per exact project. Unknown projects are disabled instead of using the development endpoint.
- Client entitlement state can distinguish server-verified `gallipick_ad_free` benefits from active Cloud storage. An ad-free-only subscriber has **zero** Cloud quota; any active paid Cloud plan includes ad removal. Both use server-verified expiry, never device settings as proof.
- Android queries Play per product, matches the exact monthly or annual base plan, and verifies purchase and restore with the server. Ad removal and Cloud expiry are checked separately. The UI suppresses a duplicate purchase while another GalliPick subscription or pending purchase is present. The production backup provider stays disabled while production billing and Google sign-in remain available.
- Production verification uses application default credentials and exact Play product/base-plan and owner checks. License-test and live records are separate. RTDN re-reads Play for locally bound purchases. Changing standalone to Cloud requires a separate Play billing replacement flow to avoid overlapping charges.

## Korea launch catalogue

All cloud plans include ad removal. Storage quotas are in decimal GB. The amounts below are the launch price proposal recorded in the Drive plan. The app reads customer-facing prices from Play Billing.

| Play product ID | Backup quota | `monthly` (P1M) | `annual` (P1Y) |
| --- | ---: | ---: | ---: |
| `gallipick_ad_free` | 0 bytes | ₩1,000 | ₩9,900 |
| `gallipick_cloud_10gb` | 10,000,000,000 bytes | ₩1,900 | ₩19,900 |
| `gallipick_cloud_50gb` | 50,000,000,000 bytes | ₩5,900 | ₩59,900 |
| `gallipick_cloud_100gb` | 100,000,000,000 bytes | ₩10,900 | ₩109,000 |

The Cloud product objects are separate from their auto-renewing base plans. Keep Cloud checkout disabled until production quotas are connected to verified entitlements, Play prices and renewals are confirmed, and the original-file limit, restoration, and retention policies are ready. The production ad-only catalogue grants zero backup bytes.

## Remaining operational work
1. **Play Console**: `gallipick_ad_free` product object does not imply a purchasable offer. Confirm the `monthly` / `annual` base plans are actually saved and ACTIVE, priced per approved market, and visible to a license tester. Separate console owner/session required.
2. **Play Developer API**: the API is enabled in `gallipick-production`. Grant the runtime service account the necessary per-app order/subscription access and validate read-only `subscriptionsv2.get` with a license-test token.
3. **Google OAuth**: the existing Play-signing Android client for `com.hebi.gallipick` is allowlisted in production Firebase. Validate the private production `google-services.json` and verify Google sign-in on a Play-signed build.
4. **Blaze and costs**: the production project uses Blaze and private Seoul Storage and Firestore. Use the cost model in the Drive plan for download and playback traffic before enabling paid Cloud plans.
5. **Production Cloud products and data**: tie the 10/50/100 GB tiers to server upload quotas. Verify the file limit, large media support, restoration, and the expiry/retention/download/deletion-notice policy before offering Cloud backup.
6. **Runtime server**: use the keyless production service account `gallipick-billing@gallipick-production.iam.gserviceaccount.com` and topic `gallipick-play-rtdn-production`. Start ad-removal validation with `GALLIPICK_PRODUCTION_AD_FREE_ENABLED=true` and `GALLIPICK_PRODUCTION_BILLING_MODE=license-test`. Set `GALLIPICK_PRODUCTION_BILLING_TEST_UIDS` for established accounts or `GALLIPICK_PRODUCTION_BILLING_TEST_EMAILS` for approved Play license testers that have yet to sign in. Firebase must confirm the Google account and verified email. Keep the test allowlist private and empty in `live`. Switch Play RTDN after validating the production receiver and verifying how to handle existing development pilot renewals.
7. **Store/release**: Google Play Data safety, app access, privacy, ad status and account/data-deletion policy are reviewed separately. Only after all code/QA gates complete and explicit approval: signed bundle from reviewed `main`, internal test, actual purchase/refund/recovery tests, and later tracks on separate authorizations.

## Required tests before production enablement
- Android debug uses `gallipick-dev` even when no release config is present; unknown and mismatched production Firebase configurations fail instead of falling back.
- Android JVM tests: Cloud-only vs ad-free-only benefit boundaries, current UID/expiry, monthly/annual recurring offer identifiers.
- Firebase Node tests: paid Cloud => ad-free; ad-free-only => 0 storage; expired, out-of-catalog, future-checked and malformed records => no benefits.
- Manual: Firebase OAuth sign-in across Play signing, two different Google accounts, network offline, account changes; prod/dev cannot query the other bucket; Play license tester purchase/change/recovery/cancellation; no default live purchase or storage behavior.
- Device/API 30/35/36 plus latest UI PR QA. Run purchase and sign-in checks from the Play internal-test install.

Links:
- GitHub issue: https://github.com/hebi10/GalliPick/issues/128
- Google Drive plan: https://docs.google.com/document/d/1nm-znFD9MXNbmYSKqruEA2VkT-PMZusoL1VoevrD-8w/edit
