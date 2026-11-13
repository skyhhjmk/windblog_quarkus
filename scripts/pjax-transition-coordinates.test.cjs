// Run with: node --test scripts/pjax-transition-coordinates.test.cjs
//
// The article transition clones are absolutely positioned in document
// coordinates. These tests pin the viewport-to-document conversion that keeps
// them scrolling with the page instead of sticking to the viewport.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(
    'src/main/resources/META-INF/resources/assets/js/main.js',
    'utf8'
);
const start = source.indexOf('        function getArticleCardViewportRect(');
const end = source.indexOf('        function nextArticleTransitionFrame(', start);

function harness({ scrollX = 0, scrollY = 0, innerHeight = 800, transform = 'none' } = {}) {
    const context = {
        window: {
            scrollX: scrollX,
            scrollY: scrollY,
            innerHeight: innerHeight,
            getComputedStyle: element => (element && element.__computedStyle) || { transform }
        }
    };
    vm.createContext(context);
    vm.runInContext(source.slice(start, end), context);
    return context;
}

test('viewport rects become document rects by adding the scroll offset', () => {
    const h = harness({ scrollX: 7, scrollY: 300 });
    assert.deepEqual(
        { ...h.toArticleTransitionDocumentRect({ left: 10, top: 20, width: 100, height: 50 }) },
        { left: 17, top: 320, width: 100, height: 50 }
    );
});

test('an explicit scroll offset wins over the live one', () => {
    const h = harness({ scrollX: 7, scrollY: 300 });
    assert.deepEqual(
        { ...h.toArticleTransitionDocumentRect({ left: 1, top: 2, width: 3, height: 4 }, { x: 0, y: 0 }) },
        { left: 1, top: 2, width: 3, height: 4 }
    );
});

test('target rects compensate the container entrance translate before converting', () => {
    const h = harness({ scrollY: 300 });
    const element = {
        getBoundingClientRect: () => ({ left: 10, top: 106, width: 100, height: 40 })
    };
    const container = {
        __computedStyle: { transform: 'matrix(1, 0, 0, 1, 0, 6)' }
    };

    const viewportRect = h.getArticleTransitionTargetRect(element, container);
    assert.equal(viewportRect.top, 100);
    assert.equal(h.toArticleTransitionDocumentRect(viewportRect).top, 400);
});

test('the settle signature is scroll-invariant', () => {
    const signatureAt = scrollY => {
        const h = harness({ scrollY: scrollY });
        const element = {
            getBoundingClientRect: () => ({ left: 20, top: 80 - scrollY, width: 300, height: 60 }),
            __computedStyle: {
                transform: 'none',
                fontSize: '32px',
                lineHeight: '40px',
                fontWeight: '700',
                letterSpacing: '0px'
            }
        };
        return h.getArticleTransitionTargetSignature([{ targetElement: element }], null);
    };

    // Scrolling during settle must not look like a layout change, otherwise the
    // stability check would never settle and always burn its 180ms deadline.
    assert.equal(signatureAt(0), signatureAt(250));
});

test('card viewport clamping keeps viewport units and converts on demand', () => {
    const h = harness({ scrollY: 400 });
    const card = {
        getBoundingClientRect: () => ({ left: 0, top: 10, width: 300, height: 200 })
    };

    const viewportRect = h.getArticleCardViewportRect(card, null);
    assert.equal(viewportRect.top, 10);
    assert.equal(h.toArticleTransitionDocumentRect(viewportRect).top, 410);
});
