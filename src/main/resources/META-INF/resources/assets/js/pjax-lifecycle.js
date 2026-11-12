// /assets/js/pjax-lifecycle.js
// The single owner of the browser-side page lifecycle. PJAX replaces
// #pjax-container without re-running scripts, so any feature that binds to page
// markup has to mount again after a navigation, while document-level bindings
// must not be repeated. Registering here removes that bookkeeping from every
// feature: the router drives beginNavigation()/mountPage() and the registry
// guarantees one mount per navigation.
//
// Page-scoped features (the default) mount once per navigation and are
// unmounted when the next navigation starts. Document-scoped features
// (`persistent: true`) mount once for the lifetime of the document, receive
// onPage() after every navigation, and still receive unmount() when one starts.
//
// mountPage() runs synchronously after the container swap and before the
// shared-element transition measures its targets (assets/js/main.js), which is
// why features that insert content above an article title must do so in mount().
(function () {
    'use strict';

    const features = new Map();
    let navigatorFn = null;
    let phase = 'idle';
    let generation = 0;
    let pageContext = Object.freeze({url: window.location.href, initial: true, generation: 0});
    let initialMounted = false;

    function makeContext(url, initial) {
        return Object.freeze({
            url: url || window.location.href,
            initial: Boolean(initial),
            generation: generation
        });
    }

    function invoke(feature, hook, context) {
        const callback = feature[hook];
        if (typeof callback !== 'function') return true;
        try {
            callback(context);
            return true;
        } catch (error) {
            console.error('[WindBlogLifecycle] ' + hook + ' failed for "' + feature.name + '"', error);
            return false;
        }
    }

    function mountRegisteredFeatures() {
        features.forEach(feature => {
            if (feature.mounted) return;
            feature.mounted = invoke(feature, 'mount', pageContext);
        });
        features.forEach(feature => {
            if (feature.persistent && feature.mounted) {
                invoke(feature, 'onPage', pageContext);
            }
        });
    }

    function register(name, definition) {
        const options = definition || {};
        const feature = {
            name: name,
            mount: options.mount,
            unmount: options.unmount,
            onPage: options.onPage,
            refresh: options.refresh,
            persistent: options.persistent === true,
            mounted: false
        };
        features.set(name, feature);
        // A script that arrives after the initial mount must not miss the
        // current page.
        if (phase === 'mounted') {
            feature.mounted = invoke(feature, 'mount', pageContext);
            if (feature.persistent && feature.mounted) {
                invoke(feature, 'onPage', pageContext);
            }
        }
        return function unregister() {
            if (features.get(name) !== feature) return;
            if (feature.mounted) invoke(feature, 'unmount', pageContext);
            features.delete(name);
        };
    }

    function beginNavigation(context) {
        if (phase === 'navigating') return;
        phase = 'navigating';
        generation += 1;
        pageContext = makeContext(context && context.url, false);
        Array.from(features.values()).reverse().forEach(feature => {
            if (!feature.mounted) return;
            invoke(feature, 'unmount', pageContext);
            if (!feature.persistent) feature.mounted = false;
        });
    }

    function mountPage(context) {
        if (phase === 'mounted') return;
        const options = context || {};
        pageContext = makeContext(options.url, options.initial);
        phase = 'mounted';
        mountRegisteredFeatures();
    }

    function refresh() {
        if (phase !== 'mounted') return;
        features.forEach(feature => {
            if (feature.mounted && typeof feature.refresh === 'function') {
                invoke(feature, 'refresh', pageContext);
            }
        });
    }

    function setNavigator(fn) {
        navigatorFn = typeof fn === 'function' ? fn : null;
    }

    function navigate(url, options) {
        if (navigatorFn) return navigatorFn(url, options);
        window.location.assign(url);
        return Promise.resolve();
    }

    function scheduleInitialMount() {
        const mountInitial = () => {
            if (initialMounted) return;
            initialMounted = true;
            if (phase !== 'idle') return;
            mountPage({url: window.location.href, initial: true});
        };
        // Deferred scripts all execute before DOMContentLoaded, so this waits
        // for every feature to register. The timeout covers documents where
        // this script is injected after DOMContentLoaded.
        document.addEventListener('DOMContentLoaded', mountInitial, {once: true});
        window.setTimeout(mountInitial, 0);
    }

    window.WindBlogLifecycle = Object.freeze({
        register: register,
        beginNavigation: beginNavigation,
        mountPage: mountPage,
        refresh: refresh,
        setNavigator: setNavigator,
        navigate: navigate
    });

    scheduleInitialMount();
})();
