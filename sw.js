// 오프라인 실행용 서비스 워커. 앱 파일만 캐시하고, 사용자 데이터는 다루지 않는다.
const VERSION = 'jangbu-v0.2.0';
const SHELL = [
  './',
  './index.html',
  './privacy.html',
  './manifest.webmanifest',
  './css/app.css',
  './src/app.js',
  './src/store.js',
  './src/parser.js',
  './src/engine.js',
  './src/dates.js',
  './src/holidays.js',
  './src/io.js',
  './src/capture.js',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// 네트워크 우선, 실패하면 캐시. 공유하기로 열린 index.html?text=... 도 캐시된 index.html로 응답한다.
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;
  e.respondWith(
    fetch(req)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(req, copy));
        }
        return res;
      })
      .catch(() => caches.match(req, { ignoreSearch: true }).then((r) => r || caches.match('./index.html'))),
  );
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(
    self.clients.matchAll({ type: 'window' }).then((list) => {
      for (const c of list) if ('focus' in c) return c.focus();
      return self.clients.openWindow('./index.html');
    }),
  );
});
