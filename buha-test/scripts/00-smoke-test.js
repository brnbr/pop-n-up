import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 1,
    duration: '5s',
};

export default function () {
    const res = http.get('http://host.docker.internal:8080/api/v1/health');
    check(res, { 'status is 200': (r) => r.status === 200 });
    sleep(1);
}