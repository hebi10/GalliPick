import { GoogleAuth, Impersonated } from 'google-auth-library';
import { BackupError } from './backup.js';
import { PLAY_BILLING_PACKAGE } from './cloud-billing.js';

const CLOUD_PLATFORM_SCOPE = 'https://www.googleapis.com/auth/cloud-platform';
const PLAY_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const PLAY_RUNTIME_ACCOUNT = 'gallipick-backup@gallipick-dev.iam.gserviceaccount.com';
const PLAY_URL = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_BILLING_PACKAGE}/purchases`;

async function scopedPlayClient() {
    const sourceClient = await new GoogleAuth({ scopes: [CLOUD_PLATFORM_SCOPE] }).getClient();
    return new Impersonated({ sourceClient, targetPrincipal: PLAY_RUNTIME_ACCOUNT, targetScopes: [PLAY_SCOPE], lifetime: 900 });
}

export function createPlaySubscriptions(getClient = scopedPlayClient) {
    let client;
    async function request(options) {
        try {
            client ??= getClient().catch(error => { client = null; throw error; });
            return (await (await client).request({ ...options, timeout: 10_000 })).data;
        } catch (error) {
            // Auth failures and provider errors can contain the token in their HTTP URL.
            if ([400, 404, 410].includes(error?.response?.status)) {
                throw new BackupError('failed-precondition', 'The subscription could not be found or confirmed by Play.');
            }
            throw new BackupError('unavailable', 'The Google Play verification service is unavailable.');
        }
    }
    return {
        get(purchaseToken) {
            return request({ method: 'GET', url: `${PLAY_URL}/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}` });
        },
        async acknowledge(purchaseToken, productId) {
            await request({ method: 'POST', data: {}, url: `${PLAY_URL}/subscriptions/${encodeURIComponent(productId)}`
                + `/tokens/${encodeURIComponent(purchaseToken)}:acknowledge` });
        },
    };
}
