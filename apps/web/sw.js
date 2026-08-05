/* FINIX service worker — cache shell + queue background sync hint. */
const CACHE = "finix-shell-v3-polished";
const SHELL = [
  "/",
  "/index.html",
  "/offline.html",
  "/transfer.html",
  "/farmer.html",
  "/sme.html",
  "/elder.html",
  "/ussd.html",
  "/lite.html",
  "/verify.html",
  "/manifest.webmanifest",
  "/icon.svg",
  "/css/theme.css",
  "/js/finix.js",
  "/js/offline.js",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/lite/")) return;
  event.respondWith(
    caches.match(req).then((cached) => cached || fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy));
      return res;
    }).catch(() => cached)),
  );
});

self.addEventListener("sync", (event) => {
  if (event.tag === "finix-offline-reconcile") {
    event.waitUntil(self.clients.matchAll().then((clients) => {
      clients.forEach((c) => c.postMessage({ type: "reconcile" }));
    }));
  }
});
