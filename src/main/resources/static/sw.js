/* Nestwerk Service Worker: zeigt Push-Benachrichtigungen an und öffnet beim Antippen die passende Seite. */

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", event => event.waitUntil(self.clients.claim()));

self.addEventListener("push", event => {
    let data = { title: "Nestwerk", body: "", url: "/" };
    try {
        if (event.data) data = Object.assign(data, event.data.json());
    } catch (e) {
        data.body = event.data ? event.data.text() : "";
    }
    event.waitUntil(self.registration.showNotification(data.title, {
        body: data.body,
        icon: "/icons/nestwerk-192.png",
        badge: "/icons/nestwerk-192.png",
        data: { url: data.url },
        lang: "de"
    }));
});

self.addEventListener("notificationclick", event => {
    event.notification.close();
    const url = (event.notification.data && event.notification.data.url) || "/";
    event.waitUntil((async () => {
        const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
        for (const client of windows) {
            if ("focus" in client && new URL(client.url).origin === new URL(url, self.location.origin).origin) {
                await client.navigate(url);
                return client.focus();
            }
        }
        return self.clients.openWindow(url);
    })());
});
