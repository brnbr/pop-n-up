import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://host.docker.internal:8080';
const VU_COUNT = Number(__ENV.VU_COUNT) || 100;
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID);

export const options = {
    setupTimeout: '10m',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        booking: {
            executor: 'per-vu-iterations',
            vus: VU_COUNT,
            iterations: 1,
            maxDuration: '3m',
        },
    },
};

export function setup() {
    if (!SCHEDULE_ID) {
        throw new Error('SCHEDULE_ID 환경변수가 필요합니다.');
    }

    const tokens = [];
    for (let i = 0; i < VU_COUNT; i++) {
        const email = `k6-cond-${Date.now()}-${i}@test.com`;
        const password = 'testPassword123!';

        const signupRes = http.post(
            `${BASE_URL}/auth/signup`,
            JSON.stringify({ email, password, name: `k6user${i}` }),
            { headers: { 'Content-Type': 'application/json' } },
        );
        check(signupRes, { 'signup success': (r) => r.status === 200 });

        const signinRes = http.post(
            `${BASE_URL}/auth/signin`,
            JSON.stringify({ email, password }),
            { headers: { 'Content-Type': 'application/json' } },
        );
        check(signinRes, { 'signin success': (r) => r.status === 200 });

        tokens.push(signinRes.headers['Authorization']);
    }

    return { tokens };
}

export default function (data) {
    const token = data.tokens[__VU - 1];

    const res = http.post(
        `${BASE_URL}/reservations`,
        JSON.stringify({ scheduleId: SCHEDULE_ID, personCount: 1 }),
        {
            headers: {
                'Content-Type': 'application/json',
                Authorization: token,
            },
        },
    );

    check(res, { 'booking succeeded (200)': (r) => r.status === 200 });
    check(res, { 'rate limited (429)': (r) => r.status === 429 });
    check(res, { 'capacity exceeded (409)': (r) => r.status === 409 });
    check(res, { 'unexpected error (5xx)': (r) => r.status >= 500 });
}