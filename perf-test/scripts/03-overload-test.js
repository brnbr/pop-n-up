import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://host.docker.internal:8080';
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID);
const TOKEN_COUNT = Number(__ENV.TOKEN_COUNT) || 100;

export const options = {
    setupTimeout: '10m',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        overload_test: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 500,
            stages: [
                { target: 50, duration: '30s' },
                { target: 100, duration: '30s' },
                { target: 200, duration: '30s' },
                { target: 400, duration: '30s' },
                { target: 800, duration: '30s' },
                { target: 1000, duration: '30s' },
                { target: 50, duration: '60s' },  // 복구 시간 관찰용
            ],
        },
    },
};

export function setup() {
    if (!SCHEDULE_ID) {
        throw new Error('SCHEDULE_ID 환경변수가 필요합니다.');
    }

    const tokens = [];
    for (let i = 0; i < TOKEN_COUNT; i++) {
        const email = `k6-overload-${Date.now()}-${i}@test.com`;
        const password = 'testPassword123!';

        http.post(
            `${BASE_URL}/auth/signup`,
            JSON.stringify({ email, password, name: `k6user${i}` }),
            { headers: { 'Content-Type': 'application/json' } },
        );

        const signinRes = http.post(
            `${BASE_URL}/auth/signin`,
            JSON.stringify({ email, password }),
            { headers: { 'Content-Type': 'application/json' } },
        );

        tokens.push(signinRes.headers['Authorization']);
    }

    return { tokens };
}

export default function (data) {
    const token = data.tokens[(__VU - 1) % data.tokens.length];
    const headers = { 'Content-Type': 'application/json', Authorization: token };

    const bookRes = http.post(
        `${BASE_URL}/reservations`,
        JSON.stringify({ scheduleId: SCHEDULE_ID, personCount: 1 }),
        { headers, tags: { step: 'book' } },
    );

    let bookCode = null;
    try {
        bookCode = JSON.parse(bookRes.body).code;
    } catch (e) {
        bookCode = 'PARSE_ERROR';
    }

    check(bookRes, {
        'book success (200)': (r) => r.status === 200,
        'book rate limited (429)': (r) => r.status === 429,
        'book capacity exceeded (409/CAPACITY)': () =>
            bookRes.status === 409 && bookCode === 'SCHEDULE_CAPACITY_EXCEEDED',
        'book duplicate - token still locked (409/DUPLICATE)': () =>
            bookRes.status === 409 && bookCode === 'DUPLICATE_USER_RESERVATION',
        'book server error (5xx)': (r) => r.status >= 500,
    });

    if (bookRes.status === 200) {
        const reservationId = JSON.parse(bookRes.body).content.reservationId;
        const cancelRes = http.del(`${BASE_URL}/reservations/${reservationId}`, null, {
            headers,
            tags: { step: 'cancel' },
        });

        let cancelCode = null;
        try {
            cancelCode = JSON.parse(cancelRes.body).code;
        } catch (e) {
            cancelCode = 'PARSE_ERROR';
        }

        check(cancelRes, {
            'cancel success (200)': (r) => r.status === 200,
            'cancel lock timeout (409/LOCK_TIMEOUT)': () =>
                cancelRes.status === 409 && cancelCode === 'LOCK_TIMEOUT',
            'cancel other failure': () =>
                cancelRes.status !== 200 && cancelCode !== 'LOCK_TIMEOUT',
        });
    }
}