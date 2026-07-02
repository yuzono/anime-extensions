package keiyoushi.lib.cloudscraper

import app.cash.quickjs.QuickJs

/**
 * Injects a comprehensive browser environment (window, navigator, document, screen, etc.)
 * into a QuickJS engine so that Cloudflare challenge scripts can execute.
 *
 * Provides **generic Chrome desktop fingerprint values** that match the default User-Agent.
 * Sites that do strict fingerprint validation beyond the JSD sensor will fall through
 * to the unsolvable-challenge path.
 *
 * @param engine the QuickJS engine to install shims into
 * @param userAgent the User-Agent string sent with challenge-solving requests;
 *   cf_clearance is tied to the UA that earned it, so this must match normal requests
 * @param originUrl the full URL of the page being challenged (used for window.location)
 */
object BrowserEnvironment {

    fun install(engine: QuickJs, userAgent: String, originUrl: String = "") {
        engine.evaluate(URL_HELPER)
        engine.evaluate(CONSOLE_SHIM)
        engine.evaluate(TIMERS_SHIM)
        engine.evaluate(ATOB_BTOA_SHIM)
        engine.evaluate(TEXT_CODEC_SHIM)
        engine.evaluate(windowShim(userAgent, originUrl))
        engine.evaluate(NAVIGATOR_SHIM)
        engine.evaluate(SCREEN_SHIM)
        engine.evaluate(documentShim(originUrl))
        engine.evaluate(PERFORMANCE_SHIM)
        engine.evaluate(CRYPTO_SHIM)
        engine.evaluate(MISC_SHIM)
    }

    // ── Window ──────────────────────────────────────────────────────

    private fun windowShim(userAgent: String, originUrl: String): String = """
        |(function() {
        |    var ua = ${jsonString(userAgent)};
        |    var url = ${jsonString(originUrl)};
        |    var _loc = __cf_parseUrl(url);
        |
        |    var win = {
        |        navigator: undefined,
        |        document: undefined,
        |        screen: undefined,
        |        performance: undefined,
        |        crypto: undefined,
        |        location: _loc,
        |        self: null,
        |        top: null,
        |        parent: null,
        |        frames: null,
        |        opener: null,
        |        closed: false,
        |        length: 0,
        |        name: '',
        |        status: '',
        |        innerWidth: 1920,
        |        innerHeight: 1080,
        |        outerWidth: 1920,
        |        outerHeight: 1040,
        |        screenX: 0,
        |        screenY: 0,
        |        pageXOffset: 0,
        |        pageYOffset: 0,
        |        devicePixelRatio: 1,
        |        visualViewport: { width: 1920, height: 1080, offsetLeft: 0, offsetTop: 0, pageLeft: 0, pageTop: 0, scale: 1 },
        |        history: { length: 1, pushState: function(){}, replaceState: function(){}, go: function(){} },
        |        localStorage: { getItem: function() { return null; }, setItem: function() {}, removeItem: function() {}, clear: function(){} },
        |        sessionStorage: { getItem: function() { return null; }, setItem: function() {}, removeItem: function() {}, clear: function(){} },
        |        getComputedStyle: function(el) {
        |            return {
        |                getPropertyValue: function(prop) { return ''; },
        |                length: 0,
        |                item: function() { return ''; }
        |            };
        |        },
        |        requestAnimationFrame: function(cb) { return setTimeout(cb, 16); },
        |        cancelAnimationFrame: function(id) { clearTimeout(id); },
        |        setTimeout: __cf_setTimeout,
        |        clearTimeout: __cf_clearTimeout,
        |        setInterval: __cf_setInterval,
        |        clearInterval: __cf_clearInterval,
        |        atob: __cf_atob,
        |        btoa: __cf_btoa,
        |        addEventListener: function() {},
        |        removeEventListener: function() {},
        |        dispatchEvent: function() { return true; },
        |        postMessage: function() {},
        |        close: function() {},
        |        blur: function() {},
        |        focus: function() {},
        |        open: function() { return null; },
        |        print: function() {},
        |        scrollX: 0,
        |        scrollY: 0,
        |        isSecureContext: true,
        |        origin: _loc.origin,
        |        crossOriginIsolated: false
        |    };
        |    win.self = win;
        |    win.top = win;
        |    win.parent = win;
        |    win.frames = win;
        |    win.navigator = __cf_nav(ua);
        |    win.document = __cf_doc(url);
        |    win.screen = __cf_screen();
        |    win.performance = __cf_perf();
        |    win.crypto = __cf_crypto();
        |    win.TextEncoder = __cf_TextEncoder;
        |    win.TextDecoder = __cf_TextDecoder;
        |
        |    globalThis.window = win;
        |    globalThis.self = win;
        |    globalThis.top = win;
        |    globalThis.parent = win;
        |    globalThis.frames = win;
        |    globalThis.navigator = win.navigator;
        |    globalThis.document = win.document;
        |    globalThis.screen = win.screen;
        |    globalThis.performance = win.performance;
        |    globalThis.crypto = win.crypto;
        |    globalThis.location = win.location;
        |    globalThis.localStorage = win.localStorage;
        |    globalThis.sessionStorage = win.sessionStorage;
        |    globalThis.getComputedStyle = win.getComputedStyle;
        |    globalThis.requestAnimationFrame = win.requestAnimationFrame;
        |    globalThis.cancelAnimationFrame = win.cancelAnimationFrame;
        |    globalThis.addEventListener = win.addEventListener;
        |    globalThis.removeEventListener = win.removeEventListener;
        |    globalThis.dispatchEvent = win.dispatchEvent;
        |    globalThis.atob = win.atob;
        |    globalThis.btoa = win.btoa;
        |    globalThis.origin = win.origin;
        |    globalThis.isSecureContext = true;
        |    globalThis.TextEncoder = __cf_TextEncoder;
        |    globalThis.TextDecoder = __cf_TextDecoder;
        |})();
    """.trimMargin()

    // ── URL Helper ───────────────────────────────────────────────────

    @JvmField val URL_HELPER = """
        |function __cf_parseUrl(url) {
        |    var loc = {
        |        href: url || '',
        |        origin: '',
        |        protocol: 'https:',
        |        host: '',
        |        hostname: '',
        |        port: '',
        |        pathname: '/',
        |        search: '',
        |        hash: '',
        |        assign: function(){},
        |        replace: function(){},
        |        reload: function(){}
        |    };
        |    if (!url) return loc;
        |    var m = url.match(/^((https?:)\/\/([^\/\?#]+))([^\?#]*)?(\?[^#]*)?(#.*)?$/);
        |    if (m) {
        |        loc.origin = m[1];
        |        loc.protocol = m[2];
        |        loc.host = m[3];
        |        loc.hostname = m[3].split(':')[0];
        |        loc.port = m[3].indexOf(':') >= 0 ? m[3].split(':')[1] : '';
        |        loc.pathname = m[4] || '/';
        |        loc.search = m[5] || '';
        |        loc.hash = m[6] || '';
        |    }
        |    return loc;
        |}
    """.trimMargin()

    // ── Console ─────────────────────────────────────────────────────

    private const val CONSOLE_SHIM = """
        globalThis.console = {
            log: function() {},
            warn: function() {},
            error: function() {},
            info: function() {},
            debug: function() {},
            trace: function() {},
            dir: function() {},
            table: function() {},
            time: function() {},
            timeEnd: function() {},
            group: function() {},
            groupEnd: function() {}
        };
    """

    // ── Timers ───────────────────────────────────────────────────────

    private const val TIMERS_SHIM = """
        var __cf_timerId = 0;
        var __cf_timers = {};
        var __cf_intervals = {};
        var __cf_pendingTimers = [];
        var __cf_pendingIntervals = [];

        function __cf_setTimeout(fn, ms) {
            var id = ++__cf_timerId;
            __cf_timers[id] = fn;
            __cf_pendingTimers.push({ id: id, fn: fn, ms: ms || 0 });
            return id;
        }
        function __cf_clearTimeout(id) { delete __cf_timers[id]; }
        function __cf_setInterval(fn, ms) {
            var id = ++__cf_timerId;
            __cf_intervals[id] = fn;
            __cf_pendingIntervals.push({ id: id, fn: fn, ms: ms || 0 });
            return id;
        }
        function __cf_clearInterval(id) { delete __cf_intervals[id]; }

        function __cf_run_timers() {
            var i;
            for (i = 0; i < __cf_pendingTimers.length; i++) {
                var t = __cf_pendingTimers[i];
                try { if (__cf_timers[t.id]) t.fn(); } catch(e) {}
                delete __cf_timers[t.id];
            }
            __cf_pendingTimers = [];
            for (i = 0; i < __cf_pendingIntervals.length; i++) {
                var s = __cf_pendingIntervals[i];
                try { if (__cf_intervals[s.id]) s.fn(); } catch(e) {}
            }
        }
    """

    // ── atob / btoa ─────────────────────────────────────────────────

    /** Pure JS atob/btoa — no Node.js Buffer dependency (QuickJS doesn't have it). */
    private const val ATOB_BTOA_SHIM = """
        function __cf_atob(s) {
            var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            var str = String(s).replace(/[^A-Za-z0-9\+\/\=]/g, '');
            var output = '';
            var i = 0;
            while (i < str.length) {
                var a = chars.indexOf(str.charAt(i++));
                var b = chars.indexOf(str.charAt(i++));
                var c = chars.indexOf(str.charAt(i++));
                var d = chars.indexOf(str.charAt(i++));
                var e = (a << 18) | (b << 12) | (c << 6) | d;
                var f = (e >> 16) & 0xFF;
                var g = (e >> 8) & 0xFF;
                var h = e & 0xFF;
                output += String.fromCharCode(f);
                if (c !== 64) output += String.fromCharCode(g);
                if (d !== 64) output += String.fromCharCode(h);
            }
            return output;
        }

        function __cf_btoa(s) {
            var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
            var str = String(s);
            var output = '';
            var i = 0;
            while (i < str.length) {
                var a = str.charCodeAt(i++);
                var b = str.charCodeAt(i++);
                var c = str.charCodeAt(i++);
                var bits = (a << 16) | ((b << 8) | c);
                output += chars[(bits >> 18) & 0x3F];
                output += chars[(bits >> 12) & 0x3F];
                output += isNaN(b) ? '=' : chars[(bits >> 6) & 0x3F];
                output += isNaN(c) ? '=' : chars[bits & 0x3F];
            }
            return output;
        }
    """

    // ── TextEncoder / TextDecoder ────────────────────────────────────

    /** Proper UTF-8 TextEncoder/TextDecoder — CF scripts rely on correct encoding. */
    private const val TEXT_CODEC_SHIM = """
        var __cf_TextEncoder = function(encoding) {
            this.encoding = encoding || 'utf-8';
        };
        __cf_TextEncoder.prototype.encode = function(s) {
            var str = String(s);
            var bytes = [];
            for (var i = 0; i < str.length; i++) {
                var code = str.charCodeAt(i);
                if (code < 0x80) {
                    bytes.push(code);
                } else if (code < 0x800) {
                    bytes.push(0xC0 | (code >> 6));
                    bytes.push(0x80 | (code & 0x3F));
                } else if (code >= 0xD800 && code <= 0xDBFF) {
                    var hi = code;
                    var lo = str.charCodeAt(++i);
                    if (lo >= 0xDC00 && lo <= 0xDFFF) {
                        var cp = ((hi - 0xD800) << 10) + (lo - 0xDC00) + 0x10000;
                        bytes.push(0xF0 | (cp >> 18));
                        bytes.push(0x80 | ((cp >> 12) & 0x3F));
                        bytes.push(0x80 | ((cp >> 6) & 0x3F));
                        bytes.push(0x80 | (cp & 0x3F));
                    }
                } else {
                    bytes.push(0xE0 | (code >> 12));
                    bytes.push(0x80 | ((code >> 6) & 0x3F));
                    bytes.push(0x80 | (code & 0x3F));
                }
            }
            return new Uint8Array(bytes);
        };

        var __cf_TextDecoder = function(encoding, opts) {
            this.encoding = (encoding || 'utf-8').toLowerCase();
            this.fatal = opts && opts.fatal || false;
            this.ignoreBOM = opts && opts.ignoreBOM || false;
        };
        __cf_TextDecoder.prototype.decode = function(buf, opts) {
            if (!buf) return '';
            var bytes;
            if (buf instanceof ArrayBuffer) {
                bytes = new Uint8Array(buf);
            } else if (buf && buf.buffer instanceof ArrayBuffer) {
                bytes = new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
            } else {
                return String(buf);
            }
            var str = '';
            var i = 0;
            while (i < bytes.length) {
                var b = bytes[i++];
                if (b < 0x80) {
                    str += String.fromCharCode(b);
                } else if (b < 0xE0) {
                    str += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i++] & 0x3F));
                } else if (b < 0xF0) {
                    str += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i++] & 0x3F) << 6) | (bytes[i++] & 0x3F));
                } else {
                    var cp = ((b & 0x07) << 18) | ((bytes[i++] & 0x3F) << 12) | ((bytes[i++] & 0x3F) << 6) | (bytes[i++] & 0x3F);
                    cp -= 0x10000;
                    str += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
                }
            }
            return str;
        };
    """

    // ── Navigator ───────────────────────────────────────────────────

    private const val NAVIGATOR_SHIM = """
        function __cf_nav(ua) {
            return {
                userAgent: ua,
                appVersion: ua.substring(ua.indexOf('/') + 1),
                platform: 'Win32',
                vendor: 'Google Inc.',
                language: 'en-US',
                languages: ['en-US', 'en'],
                onLine: true,
                cookieEnabled: true,
                doNotTrack: null,
                maxTouchPoints: 0,
                hardwareConcurrency: 8,
                deviceMemory: 8,
                connection: { effectiveType: '4g', rtt: 100, downlink: 10, saveData: false },
                plugins: { length: 5, item: function() { return null; }, namedItem: function() { return null; }, refresh: function() {} },
                mimeTypes: { length: 2, item: function() { return null; }, namedItem: function() { return null; } },
                webdriver: false,
                getBattery: function() { return Promise.resolve({ charging: true, chargingTime: 0, dischargingTime: Infinity, level: 1 }); },
                getGamepads: function() { return []; },
                sendBeacon: function() { return true; },
                javaEnabled: function() { return false; },
                mediaDevices: { enumerateDevices: function() { return Promise.resolve([]); } },
                permissions: { query: function() { return Promise.resolve({ state: 'prompt' }); } },
                serviceWorker: { ready: Promise.resolve({}), register: function() { return Promise.resolve({}); } },
                clipboard: { readText: function() { return Promise.resolve(''); }, writeText: function() { return Promise.resolve(); } },
                locks: { request: function() { return Promise.resolve(); }, query: function() { return Promise.resolve({ held: [], pending: [] }); } },
                storage: { estimate: function() { return Promise.resolve({ quota: 1073741824, usage: 0 }); } },
                userAgentData: {
                    brands: [
                        { brand: 'Chromium', version: '131' },
                        { brand: 'Google Chrome', version: '131' },
                        { brand: 'Not_A Brand', version: '99' }
                    ],
                    mobile: false,
                    platform: 'Windows',
                    getHighEntropyValues: function() { return Promise.resolve({ architecture: 'x86', model: '', platform: 'Windows', platformVersion: '15.0.0', uaFullVersion: ua }); }
                },
                globalPrivacyControl: false
            };
        }
    """

    // ── Screen ───────────────────────────────────────────────────────

    private const val SCREEN_SHIM = """
        function __cf_screen() {
            return {
                width: 1920,
                height: 1080,
                availWidth: 1920,
                availHeight: 1040,
                colorDepth: 24,
                pixelDepth: 24,
                orientation: { angle: 0, type: 'landscape-primary', onchange: null },
                isExtended: false
            };
        }
    """

    // ── Document ────────────────────────────────────────────────────

    private fun documentShim(originUrl: String): String = """
        |function __cf_doc(url) {
        |    var _docUrl = url || '';
        |    var _parsedUrl = __cf_parseUrl(_docUrl);
        |    var _domain = _parsedUrl.hostname;
        |
        |    var el = function(tag) {
        |        return {
        |            tagName: tag.toUpperCase(),
        |            nodeName: tag.toUpperCase(),
        |            children: [],
        |            childNodes: [],
        |            style: new Proxy({}, { get: function(t, p) { return p === 'length' ? 0 : ''; }, set: function() { return true; } }),
        |            attributes: [],
        |            classList: { add: function(){}, remove: function(){}, contains: function(){ return false; }, toggle: function(){ return false; }, item: function(){ return null; } },
        |            querySelector: function() { return null; },
        |            querySelectorAll: function() { return []; },
        |            getElementsByTagName: function() { return []; },
        |            getElementsByClassName: function() { return []; },
        |            getElementById: function() { return null; },
        |            addEventListener: function() {},
        |            removeEventListener: function() {},
        |            setAttribute: function() {},
        |            getAttribute: function() { return null; },
        |            hasAttribute: function() { return false; },
        |            removeAttribute: function() {},
        |            appendChild: function(c) { this.children.push(c); this.childNodes.push(c); c.parentNode = this; c.parentElement = this; return c; },
        |            removeChild: function(c) { return c; },
        |            insertBefore: function(n) { this.children.unshift(n); this.childNodes.unshift(n); return n; },
        |            replaceChild: function(n, o) { return o; },
        |            cloneNode: function(deep) { return this; },
        |            contains: function() { return false; },
        |            matches: function() { return false; },
        |            closest: function() { return null; },
        |            parentNode: null,
        |            parentElement: null,
        |            firstChild: null,
        |            lastChild: null,
        |            nextSibling: null,
        |            previousSibling: null,
        |            firstElementChild: null,
        |            lastElementChild: null,
        |            nextElementSibling: null,
        |            previousElementSibling: null,
        |            ownerDocument: null,
        |            textContent: '',
        |            innerHTML: '',
        |            innerText: '',
        |            outerHTML: '',
        |            nodeType: 1,
        |            nodeValue: null,
        |            nodeName: tag.toUpperCase(),
        |            offsetWidth: 0,
        |            offsetHeight: 0,
        |            offsetTop: 0,
        |            offsetLeft: 0,
        |            clientWidth: 0,
        |            clientHeight: 0,
        |            clientTop: 0,
        |            clientLeft: 0,
        |            scrollWidth: 0,
        |            scrollHeight: 0,
        |            scrollTop: 0,
        |            scrollLeft: 0,
        |            dataset: new Proxy({}, { get: function() { return undefined; }, set: function() { return true; } }),
        |            getBoundingClientRect: function() { return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0, toJSON: function(){ return this; } }; },
        |            getClientRects: function() { return { length: 0, item: function(){ return null; } }; },
        |            focus: function() {},
        |            blur: function() {},
        |            click: function() {},
        |            dispatchEvent: function() { return true; },
        |            attachEvent: function() {},
        |            detachEvent: function() {}
        |        };
        |    };
        |
        |    var __cf_elements = {};
        |    var __cf_elementId = 0;
        |
        |    var doc = el('#document');
        |    doc.nodeType = 9;
        |
        |    var head = el('head');
        |    var body = el('body');
        |    doc.head = head;
        |    doc.body = body;
        |    doc.documentElement = el('html');
        |    doc.documentElement.appendChild(head);
        |    doc.documentElement.appendChild(body);
        |
        |    doc.cookie = '';
        |    doc.title = '';
        |    doc.domain = _domain;
        |    doc.URL = _docUrl;
        |    doc.documentURI = _docUrl;
        |    doc.referrer = '';
        |    doc.readyState = 'complete';
        |    doc.visibilityState = 'visible';
        |    doc.hidden = false;
        |    doc.characterSet = 'UTF-8';
        |    doc.charset = 'UTF-8';
        |    doc.inputEncoding = 'UTF-8';
        |    doc.contentType = 'text/html';
        |    doc.compatMode = 'CSS1Compat';
        |    doc.designMode = 'off';
        |    doc.fullscreenElement = null;
        |    doc.fullscreenEnabled = true;
        |    doc.pictureInPictureElement = null;
        |    doc.pictureInPictureEnabled = false;
        |    doc.pointerLockElement = null;
        |    doc.activeElement = body;
        |    doc.currentScript = null;
        |    doc.defaultView = null;
        |
        |    doc.createElement = function(tag) { var e = el(tag); e.nodeName = tag.toUpperCase(); e.tagName = tag.toUpperCase(); e.ownerDocument = doc; return e; };
        |    doc.createElementNS = function(ns, tag) { return doc.createElement(tag); };
        |    doc.createDocumentFragment = function() { var f = el('#fragment'); f.nodeType = 11; return f; };
        |    doc.createTextNode = function(t) { return { nodeType: 3, textContent: t, data: t, nodeName: '#text' }; };
        |    doc.createComment = function(t) { return { nodeType: 8, data: t, nodeName: '#comment' }; };
        |    doc.createEvent = function(type) { return { initEvent: function(){}, preventDefault: function(){}, stopPropagation: function(){} }; };
        |    doc.createTreeWalker = function() { return { nextNode: function(){ return null; } }; };
        |    doc.createRange = function() { return { setStart: function(){}, setEnd: function(){}, createContextualFragment: function(html) { var d = el('div'); d.innerHTML = html; return d; } }; };
        |
        |    doc.getElementById = function(id) {
        |        if (__cf_elements[id] !== undefined) return __cf_elements[id];
        |        if (id === 'challenge-form' || id === 'challenge-platform') {
        |            var form = el('form');
        |            form.id = id;
        |            form.action = '';
        |            form.method = 'POST';
        |            form.submit = function() {};
        |            form.querySelector = function(sel) {
        |                if (sel === 'input[name="r"]') return { value: '', name: 'r' };
        |                return null;
        |            };
        |            form.querySelectorAll = function() { return []; };
        |            __cf_elements[id] = form;
        |            return form;
        |        }
        |        if (id === 'jschl-answer' || id === 'jschl_answer') {
        |            var input = el('input');
        |            input.id = id;
        |            input.name = 'jschl_answer';
        |            input.value = '';
        |            input.type = 'hidden';
        |            __cf_elements[id] = input;
        |            return input;
        |        }
        |        return null;
        |    };
        |    doc.getElementsByTagName = function(tag) { tag = tag.toLowerCase(); if (tag === 'head') return [head]; if (tag === 'body') return [body]; if (tag === 'html') return [doc.documentElement]; if (tag === 'script') return []; return []; };
        |    doc.getElementsByClassName = function() { return []; };
        |    doc.querySelector = function(sel) { if (sel === 'head') return head; if (sel === 'body') return body; if (sel === '#challenge-form') return doc.getElementById('challenge-form'); return null; };
        |    doc.querySelectorAll = function() { return []; };
        |    doc.addEventListener = function() {};
        |    doc.removeEventListener = function() {};
        |    doc.dispatchEvent = function() { return true; };
        |    doc.hasFocus = function() { return true; };
        |
        |    doc.__cf_elements = __cf_elements;
        |    doc.defaultView = globalThis.window || null;
        |
        |    return doc;
        |}
    """.trimMargin()

    // ── Performance ─────────────────────────────────────────────────

    private const val PERFORMANCE_SHIM = """
        function __cf_perf() {
            var ts = Date.now();
            return {
                now: function() { return Date.now() - ts; },
                timing: {
                    navigationStart: ts - 500,
                    unloadEventStart: 0,
                    unloadEventEnd: 0,
                    redirectStart: 0,
                    redirectEnd: 0,
                    fetchStart: ts - 400,
                    domainLookupStart: ts - 380,
                    domainLookupEnd: ts - 370,
                    connectStart: ts - 360,
                    connectEnd: ts - 300,
                    secureConnectionStart: ts - 340,
                    requestStart: ts - 280,
                    responseStart: ts - 200,
                    responseEnd: ts - 100,
                    domLoading: ts - 90,
                    domInteractive: ts - 50,
                    domContentLoadedEventStart: ts - 40,
                    domContentLoadedEventEnd: ts - 39,
                    domComplete: ts - 10,
                    loadEventStart: ts - 5,
                    loadEventEnd: ts
                },
                navigation: { type: 0, redirectCount: 0 },
                timeOrigin: ts - 500,
                getEntries: function() { return []; },
                getEntriesByType: function() { return []; },
                getEntriesByName: function() { return []; },
                mark: function() {},
                measure: function() {},
                clearMarks: function() {},
                clearMeasures: function() {},
                setResourceTimingBufferSize: function() {}
            };
        }
    """

    // ── Crypto ──────────────────────────────────────────────────────

    private const val CRYPTO_SHIM = """
        function __cf_crypto() {
            return {
                subtle: {
                    digest: function() { return Promise.resolve(new ArrayBuffer(32)); },
                    importKey: function() { return Promise.resolve({}); },
                    exportKey: function() { return Promise.resolve({}); },
                    sign: function() { return Promise.resolve(new ArrayBuffer(64)); },
                    verify: function() { return Promise.resolve(true); },
                    encrypt: function() { return Promise.resolve(new ArrayBuffer(0)); },
                    decrypt: function() { return Promise.resolve(new ArrayBuffer(0)); },
                    generateKey: function() { return Promise.resolve({}); },
                    deriveBits: function() { return Promise.resolve(new ArrayBuffer(32)); },
                    deriveKey: function() { return Promise.resolve({}); }
                },
                getRandomValues: function(arr) {
                    for (var i = 0; i < arr.length; i++) {
                        arr[i] = Math.floor(Math.random() * 256);
                    }
                    return arr;
                },
                randomUUID: function() {
                    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
                        var r = Math.random() * 16 | 0;
                        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
                    });
                }
            };
        }
    """

    // ── Misc globals ────────────────────────────────────────────────

    private const val MISC_SHIM = """
        globalThis.fetch = function(url, opts) {
            if (opts && opts.method === 'POST' && opts.body) {
                globalThis.__cf_sensor_payload = typeof opts.body === 'string' ? opts.body : String(opts.body);
                globalThis.__cf_sensor_url = typeof url === 'string' ? url : String(url);
            }
            return Promise.resolve({ ok: true, status: 200, headers: new Map(), json: function() { return Promise.resolve({}); }, text: function() { return Promise.resolve(''); }, clone: function() { return this; } });
        };

        globalThis.XMLHttpRequest = function() {
            this.readyState = 0;
            this.status = 0;
            this.responseText = '';
            this.response = '';
            this.responseType = '';
            this.withCredentials = false;
            this._method = '';
            this._url = '';
            this._headers = {};
            this._async = true;
        };
        globalThis.XMLHttpRequest.prototype.open = function(method, url, async) {
            this._method = method;
            this._url = url;
            this._async = async !== false;
            this.readyState = 1;
        };
        globalThis.XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
            this._headers[k] = v;
        };
        globalThis.XMLHttpRequest.prototype.send = function(body) {
            if (this._method === 'POST' && body) {
                globalThis.__cf_sensor_payload = typeof body === 'string' ? body : String(body);
                globalThis.__cf_sensor_url = this._url;
            }
            this.readyState = 4;
            this.status = 200;
            if (this.onreadystatechange) this.onreadystatechange();
            if (this.onload) this.onload();
        };
        globalThis.XMLHttpRequest.prototype.getResponseHeader = function() { return null; };
        globalThis.XMLHttpRequest.prototype.getAllResponseHeaders = function() { return ''; };
        globalThis.XMLHttpRequest.prototype.abort = function() {};

        globalThis.Event = function(type) { this.type = type; this.bubbles = false; this.cancelable = false; };
        globalThis.CustomEvent = function(type, opts) { this.type = type; this.detail = opts && opts.detail; };
        globalThis.MessageChannel = function() { this.port1 = { postMessage: function(){}, onmessage: null }; this.port2 = { postMessage: function(){}, onmessage: null }; };
        globalThis.MutationObserver = function() { return { observe: function(){}, disconnect: function(){}, takeRecords: function(){ return []; } }; };
        globalThis.IntersectionObserver = function() { return { observe: function(){}, disconnect: function(){}, unobserve: function(){} }; };
        globalThis.ResizeObserver = function() { return { observe: function(){}, disconnect: function(){}, unobserve: function(){} }; };
        globalThis.PerformanceObserver = function() { return { observe: function(){}, disconnect: function(){} }; };
        globalThis.Worker = function() {};
        globalThis.SharedWorker = function() {};
        globalThis.ServiceWorker = function() {};
        globalThis.Blob = function(parts, opts) { this.size = 0; this.type = (opts && opts.type) || ''; this.parts = parts; };
        globalThis.File = function(parts, name, opts) { this.name = name; this.size = 0; this.type = (opts && opts.type) || ''; };
        globalThis.FileReader = function() { this.result = null; this.readAsDataURL = function(){}; this.readAsText = function(){}; this.readAsArrayBuffer = function(){}; };
        globalThis.URL = { createObjectURL: function(){ return 'blob:null'; }, revokeObjectURL: function(){}, prototype: globalThis.URL };
        globalThis.URLSearchParams = function(init) { this._params = []; this.get = function(k){ return null; }; this.set = function(){}; this.has = function(k){ return false; }; this.toString = function(){ return ''; }; this.forEach = function(){}; this.append = function(){}; this.delete = function(){}; this.entries = function(){ return []; }; this.keys = function(){ return []; }; this.values = function(){ return []; }; };
        globalThis.FormData = function() { this._data = {}; this.append = function(k,v){ this._data[k] = v; }; this.get = function(k){ return this._data[k]; }; this.has = function(k){ return k in this._data; }; this.toString = function(){ return ''; }; };
        globalThis.DOMParser = function() { this.parseFromString = function(s, type) { return globalThis.document; }; };
        globalThis.AbortController = function() { this.signal = { aborted: false, reason: undefined, onabort: null }; this.abort = function() { this.signal.aborted = true; }; };
        globalThis.Promise = Promise;
        globalThis.Proxy = Proxy;
        globalThis.Reflect = Reflect;
        globalThis.Map = Map;
        globalThis.Set = Set;
        globalThis.WeakMap = WeakMap;
        globalThis.WeakSet = WeakSet;
        globalThis.Symbol = Symbol;

        globalThis.Image = function() { return { src: '', onload: null, onerror: null, complete: true, width: 0, height: 0 }; };
        globalThis.Audio = function() { return { src: '', play: function() { return Promise.resolve(); }, pause: function(){} }; };
        globalThis.Notification = function() {};
        globalThis.Notification.permission = 'default';
        globalThis.Notification.requestPermission = function() { return Promise.resolve('denied'); };

        globalThis.webkitRTCPeerConnection = function() {};
        globalThis.RTCPeerConnection = function() {};
        globalThis.RTCSessionDescription = function() {};
        globalThis.RTCIceCandidate = function() {};
        globalThis.MediaStream = function() {};
        globalThis.MediaRecorder = function() {};

        globalThis.HTMLElement = function() {};
        globalThis.HTMLInputElement = function() {};
        globalThis.HTMLFormElement = function() {};
        globalThis.HTMLCanvasElement = function() { this.getContext = function() { return { fillRect: function(){}, fillText: function(){}, getImageData: function(){ return { data: new Uint8Array(0) }; }, toDataURL: function(){ return ''; }, measureText: function(){ return { width: 0 }; }, arc: function(){}, beginPath: function(){}, closePath: function(){}, fill: function(){}, lineTo: function(){}, moveTo: function(){}, stroke: function(){}, createLinearGradient: function(){ return { addColorStop: function(){} }; } }; }; this.toDataURL = function() { return 'data:image/png;base64,'; }; };
        globalThis.HTMLVideoElement = function() {};
        globalThis.HTMLAudioElement = function() {};
        globalThis.HTMLScriptElement = function() {};
        globalThis.HTMLIFrameElement = function() { this.contentWindow = null; };
    """

    // ── Helpers ──────────────────────────────────────────────────────

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
