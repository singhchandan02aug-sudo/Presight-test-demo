import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 5,
  duration: '20s',
};

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';

export default function () {
  const articles = http.get(`${BASE_URL}/api/articles`);
  check(articles, {
    'articles GET is healthy': (r) => r.status === 200,
  });

  const tags = http.get(`${BASE_URL}/api/tags`);
  check(tags, {
    'tags GET is healthy': (r) => r.status === 200,
  });

  sleep(1);
}
