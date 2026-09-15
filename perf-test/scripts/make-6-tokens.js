import http from 'k6/http';

const BASE_URL = 'http://host.docker.internal:8080';

export const options = {
    vus: 1,
    iterations: 1,
};

export default function () {
    for (let i = 0; i < 6; i++) {
        const email = `ratelimit-test-${Date.now()}-${i}@test.com`;
        const password = 'testPassword123!';

        http.post(`${BASE_URL}/auth/signup`,
            JSON.stringify({ email, password, name: `rl${i}` }),
            { headers: { 'Content-Type': 'application/json' } });

        const signinRes = http.post(`${BASE_URL}/auth/signin`,
            JSON.stringify({ email, password }),
            { headers: { 'Content-Type': 'application/json' } });

        console.log(`token${i}: ${signinRes.headers['Authorization']}`);
    }
}