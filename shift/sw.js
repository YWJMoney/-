// 내 근무 달력 [I50c-02]: 홈 화면에서 연 달력이 인터넷이 잠깐 없을 때도 열리게 해요.
// 늘 먼저 인터넷에서 받고(새 판이 바로 보여요), 못 받을 때만 지난번에 받아 둔 것을 써요. 이 사이트 파일만 다루고 바깥으로 보내는 것은 없어요.
var KEEP = 'bn-shift-1';
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) {
    return Promise.all(ks.filter(function (k) { return k.indexOf('bn-shift-') === 0 && k !== KEEP; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var r = e.request, u = new URL(r.url);
  if (r.method !== 'GET' || u.origin !== location.origin) return;
  // 쪽은 주소의 ?p=…&s=… 를 떼고 한 장만 둬요(내 순서는 주소와 브라우저에 남아 있어요).
  var key = r.mode === 'navigate' ? u.origin + u.pathname : r;
  e.respondWith(fetch(r).then(function (res) {
    if (res.ok) { var copy = res.clone(); caches.open(KEEP).then(function (c) { c.put(key, copy); }); }
    return res;
  }).catch(function () {
    return caches.match(key).then(function (m) { return m || Response.error(); });
  }));
});
