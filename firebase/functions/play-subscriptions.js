import { GoogleAuth } from 'google-auth-library';
import { BackupError } from './backup.js';
import { PLAY_BILLING_PACKAGE } from './cloud-billing.js';

const PLAY_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const PLAY_URL = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PLAY_BILLING_PACKAGE}/purchases`;

async function scopedPlayClient() {
    return new GoogleAuth({ scopes: [PLAY_SCOPE] }).getClient();
}

export function createPlaySubscriptions(getClient = scopedPlayClient) {
    let client;
    async function request(options) {
        try {
            client ??= getClient().catch(error => { client = null; throw error; });
            return (await (await client).request({ ...options, timeout: 10_000 })).data;
        } catch (error) {
            // Auth failures and provider errors can contain the token in their HTTP URL.
            const status = error?.response?.status;
            if (options.method === 'GET' && status === 410) {
                throw new BackupError('not-found', 'The Play subscription is no longer available.');
            }
            if ([400, 404, 410].includes(status)) {
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
