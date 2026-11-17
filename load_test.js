
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ========================================
// FISCALMOTION PERFORMANCE TEST
// ========================================
//
// Modes:
//   baseline -> 1 virtual user, 60 seconds
//   load     -> ramp up to 50 virtual users
//
// Measures:
//   - Successful transfer latency
//   - p50, p90, p95, p99
//   - Successful transfers
//   - HTTP 429 rate limiting
//   - Unexpected errors
// ========================================

const MODE = __ENV.MODE || 'baseline';

const BASE_URL = (
    __ENV.FM_BASE_URL || 'http://localhost:8080'
).replace(/\/$/, '');

const EMAIL = __ENV.FM_EMAIL;
const PASSWORD = __ENV.FM_PASSWORD;

const SOURCE_ID = __ENV.FM_SOURCE_ID;
const TARGET_ID = __ENV.FM_TARGET_ID;

const AMOUNT = __ENV.FM_AMOUNT || '1.0000';

// ========================================
// TEST CONFIGURATION
// ========================================

export const options = {
    noCookiesReset: true,
    scenarios: MODE === 'baseline'
        ? {
            baseline: {
                executor: 'constant-vus',
                vus: 1,
                duration: '60s',
            },
        }
        : {
            load: {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: [
                    {
                        duration: '10s',
                        target: 20,
                    },
                    {
                        duration: '30s',
                        target: 50,
                    },
                    {
                        duration: '10s',
                        target: 0,
                    },
                ],
                gracefulRampDown: '10s',
            },
        },

    thresholds: {
        transfer_latency: [
            'p(95)<300',
            'p(99)<500',
        ],

        unexpected_response_rate: [
            'rate<0.01',
        ],
    },
};

// ========================================
// CUSTOM METRICS
// ========================================

const transferLatency = new Trend(
    'transfer_latency',
    true
);

const successfulTransfers = new Counter(
    'successful_transfers'
);

const rateLimitedTransfers = new Counter(
    'rate_limited_transfers'
);

const unexpectedResponses = new Counter(
    'unexpected_transfer_responses'
);

const unexpectedResponseRate = new Rate(
    'unexpected_response_rate'
);

// ========================================
// PER-VIRTUAL-USER SESSION
// ========================================

let loggedIn = false;

let csrfHeader = 'X-XSRF-TOKEN';
let csrfToken = '';

// ========================================
// FETCH CSRF TOKEN
// ========================================

function refreshCsrf() {

    const response = http.get(
        `${BASE_URL}/api/v1/auth/csrf`,
        {
            tags: {
                operation: 'csrf',
            },
        }
    );

    if (response.status !== 200) {
        fail(
            `CSRF failed: HTTP ${response.status} - ${response.body}`
        );
    }

    let body;

    try {
        body = response.json();
    } catch (e) {
        fail(
            `Invalid CSRF response: ${response.body}`
        );
    }

    csrfHeader = body.headerName || 'X-XSRF-TOKEN';
    csrfToken = body.token;

    if (!csrfToken) {
        fail('CSRF token missing');
    }
}

// ========================================
// LOGIN
// ========================================

function login() {

    if (
        !EMAIL ||
        !PASSWORD ||
        !SOURCE_ID ||
        !TARGET_ID
    ) {
        fail(
            'Missing FM_EMAIL, FM_PASSWORD, ' +
            'FM_SOURCE_ID or FM_TARGET_ID'
        );
    }

    refreshCsrf();

    const payload = JSON.stringify({
        email: EMAIL,
        password: PASSWORD,
    });

    const response = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        payload,
        {
            headers: {
                'Content-Type': 'application/json',
                [csrfHeader]: csrfToken,
            },

            tags: {
                operation: 'login',
            },
        }
    );

    if (response.status !== 200) {

        fail(
            `Login failed: HTTP ${response.status} - ${response.body}`
        );
    }

    // Login rotates the session ID.
    // Obtain the current CSRF token afterward.
    refreshCsrf();

    loggedIn = true;
}

// ========================================
// MAIN TRANSFER TEST
// ========================================

export default function () {

    // Login once per virtual user.
    if (!loggedIn) {
        login();
    }

    // Unique key for every transfer request.
    const idempotencyKey =
        `K6-${MODE}-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({

        sourceAccountId: SOURCE_ID,

        targetAccountId: TARGET_ID,

        amount: Number(AMOUNT),
    });

    const response = http.post(
        `${BASE_URL}/api/v1/ledger/transfers`,
        payload,
        {
            headers: {

                'Content-Type': 'application/json',

                'Idempotency-Key': idempotencyKey,

                [csrfHeader]: csrfToken,
            },

            tags: {
                operation: 'transfer',
            },

            responseType: 'text',
        }
    );

    // ====================================
    // SUCCESSFUL TRANSFER
    // ====================================

    if (response.status === 201) {

        successfulTransfers.add(1);

        transferLatency.add(
            response.timings.duration
        );

        unexpectedResponseRate.add(false);

    }

        // ====================================
        // RATE LIMITED
    // ====================================

    else if (response.status === 429) {

        rateLimitedTransfers.add(1);

        unexpectedResponseRate.add(false);

    }

        // ====================================
        // UNEXPECTED RESPONSE
    // ====================================

    else {

        unexpectedResponses.add(1);
        unexpectedResponseRate.add(true);

        if (response.status === 401) {

            console.error(
                `HTTP 401 for ${idempotencyKey}. ` +
                `Session may have expired or become invalid.`
            );

            // Login again on the next iteration.
            loggedIn = false;

        } else {

            console.error(
                `Unexpected HTTP ${response.status} ` +
                `for ${idempotencyKey}: ` +
                `${String(response.body).slice(0, 350)}`
            );
        }
    }

    check(response, {

        'transfer returned 201 or 429': (r) =>
            r.status === 201 || r.status === 429,
    });

    // Think time between requests.
    sleep(
        MODE === 'baseline' ? 0.4 : 0.1
    );
}