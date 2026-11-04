import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '10s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '10s', target: 0 },
    ],
    thresholds: {
        http_req_duration: ['p(95)<300', 'p(99)<500'],
        // Fail only if unexpected error codes (5xx, 400, etc.) exceed 1%
        'http_req_failed{status:not 429}': ['rate<0.01'],
    },
};

// Replace these UUIDs with actual account UUIDs from your PostgreSQL database
const SOURCE_ACCOUNT_ID = 'd2e27e45-e132-4206-bd19-6d61e0270a88';
const TARGET_ACCOUNT_ID = '4a61388b-6f2b-4fc5-b95f-d7278c43d7e9';

export default function () {
    const url = 'http://localhost:8080/api/v1/ledger/transfers';

    // Generate unique Idempotency Key per VU and iteration
    const idempotencyKey = `K6-BENCH-${__VU}-${__ITER}-${Date.now()}`;

    const payload = JSON.stringify({
        sourceAccountId: SOURCE_ACCOUNT_ID,
        targetAccountId: TARGET_ACCOUNT_ID,
        amount: 100.00,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
    };

    const res = http.post(url, payload, params);

    check(res, {
        'status is 201 or 429': (r) => r.status === 201 || r.status === 429,
    });

    sleep(0.1); // Small think-time delay between requests
}