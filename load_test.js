import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '10s', target: 20 },  // Ramp-up to 20 virtual users over 10s
        { duration: '30s', target: 50 },  // Sustained load at 50 virtual users
        { duration: '10s', target: 0 },   // Ramp-down to 0
    ],
    thresholds: {
        http_req_duration: ['p(95)<200', 'p(99)<500'], // 95% of requests < 200ms, 99% < 500ms
        http_req_failed: ['rate<0.01'],               // Less than 1% failure rate
    },
};

// Replace these UUIDs with actual account UUIDs from your PostgreSQL database
const SOURCE_ACCOUNT_ID = '662b3430-d61b-469f-bf9d-32b3ef21389b';
const TARGET_ACCOUNT_ID = 'e29d3d60-e038-4b25-95eb-6731ecbc8d33';

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
        'status is 201 Created': (r) => r.status === 201,
    });

    sleep(0.1); // Small think-time delay between requests
}