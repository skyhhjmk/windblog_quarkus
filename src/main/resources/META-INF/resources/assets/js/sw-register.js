// WindBlog Service Worker Unregister & Cache Cleanup (/assets/js/sw-register.js)
(function () {
    'use strict';

    if (typeof window === 'undefined') {
        return;
    }

    function purgeServiceWorkerAndCaches() {
        const banner = document.getElementById('windblog-offline-banner');
        if (banner && banner.parentNode) {
            banner.parentNode.removeChild(banner);
        }

        if ('serviceWorker' in navigator && typeof navigator.serviceWorker.getRegistrations === 'function') {
            navigator.serviceWorker.getRegistrations()
                .then(function (registrations) {
                    return Promise.all(registrations.map(function (reg) {
                        return reg.unregister().catch(function () { return false; });
                    }));
                })
                .catch(function () {});
        }

        if (typeof caches !== 'undefined' && typeof caches.keys === 'function') {
            caches.keys()
                .then(function (keys) {
                    return Promise.all(keys
                        .filter(function (key) { return key.indexOf('windblog-') === 0; })
                        .map(function (key) { return caches.delete(key); }));
                })
                .catch(function () {});
        }
    }

    purgeServiceWorkerAndCaches();
})();
