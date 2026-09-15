import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = 'http://host.docker.internal:8080';
const SCHEDULE_ID_START = Number(__ENV.SCHEDULE_ID_START);
const SCHEDULE_COUNT = Number(__ENV.SCHEDULE_COUNT) || 50;
const TOKEN_COUNT = Number(__ENV.TOKEN_COUNT) || 2000;

export const options = {
    setupTimeout: '10m',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        overload_test: {
            executor: 'ramping-arrival-rate',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 500,
            maxVUs: 2000,
            stages: [
                { target: 50, duration: '30s' },
                { target: 100, duration: '30s' },
                { target: 200, duration: '30s' },
                { target: 400, duration: '30s' },
                { target: 800, duration: '30s' },
                { target: 1000, duration: '30s' },
                { target: 50, duration: '60s' },
            ],
        },
    },
};

export function setup() {
    if (!SCHEDULE_ID_START) {
        throw new Error('SCHEDULE_ID_START 환경변수가 필요합니다.');
    }

    const tokens = [];
    for (let i = 0; i < TOKEN_COUNT; i++) {
        const email = `k6-tput-${Date.now()}-${i}@test.com`;
        const password = 'testPassword123!';

        http.post(`${BASE_URL}/auth/signup`,
            JSON.stringify({ email, password, name: `k6user${i}` }),
            { headers: { 'Content-Type': 'application/json' } });

        const signinRes = http.post(`${BASE_URL}/auth/signin`,
            JSON.stringify({ email, password }),
            { headers: { 'Content-Type': 'application/json' } });

        tokens.push(signinRes.headers['Authorization']);
    }
    return { tokens };
}

export default function (data) {
    // 테스트 전체를 통틀어 순증하는 전역 인덱스.
    // 같은 토큰은 SCHEDULE_COUNT개 스케줄을 다 쓴 뒤에야(=TOKEN_COUNT번 뒤에야) 다시 등장하므로
    // 총 요청 수가 TOKEN_COUNT * SCHEDULE_COUNT를 넘지 않는 한 같은 (토큰,스케줄) 조합이 절대 반복되지 않는다.
    const idx = exec.scenario.iterationInTest;
    const token = data.tokens[idx % TOKEN_COUNT];
    const scheduleId = SCHEDULE_ID_START + (Math.floor(idx / TOKEN_COUNT) % SCHEDULE_COUNT);

    const headers = { 'Content-Type': 'application/json', Authorization: token };

    const bookRes = http.post(
        `${BASE_URL}/reservations`,
        JSON.stringify({ scheduleId, personCount: 1 }),
        { headers, tags: { step: 'book' } },
    );

    check(bookRes, {
        'book success (200)': (r) => r.status === 200,
        'book rate limited (429)': (r) => r.status === 429,
        'book duplicate (409)': (r) => r.status === 409,
        'book server error (5xx)': (r) => r.status >= 500,
        'connection failed (timeout)': (r) => r.status === 0,
    });
}