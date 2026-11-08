// WindBlog Self-Destroying Service Worker (/sw.js)
//
// Immediately replaces any previously installed Service Worker, purges all
// WindBlog Cache Storage buckets, unregisters itself, and reloads controlled
// tabs so they return to direct network navigation.
(function () {
    'use strict';

    self.addEventListener('install', function () {
        self.skipWaiting();
    });

    self.addEventListener('activate', function (event) {
        event.waitUntil((async function () {
            try {
                if (typeof caches !== 'undefined' && caches.keys) {
                    const keys = await caches.keys();
                    await Promise.all(keys.map(function (key) {
                        return caches.delete(key);
                    }));
                }
            } catch (e) {
                // Ignore cache deletion errors.
            }

            try {
                await self.registration.unregister();
            } catch (e) {
                // Ignore unregister errors.
            }

            try {
                const clientList = await self.clients.matchAll({
                    type: 'window',
                    includeUncontrolled: false
                });
                for (const client of clientList) {
                    if (client && client.url && typeof client.navigate === 'function') {
                        try {
                            await client.navigate(client.url);
                        } catch (navErr) {
                            // Cross-origin or closing tab; ignore.
                        }
                    }
                }
            } catch (e) {
                // Ignore client matching errors.
            }
        })());
    });
})();
