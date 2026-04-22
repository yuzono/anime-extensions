(window.webpackJsonp = window.webpackJsonp || []).push([
  [1],
  {
    0: function (t, e, n) {
      "use strict";
      (n.d(e, "k", function () {
        return y;
      }),
        n.d(e, "m", function () {
          return w;
        }),
        n.d(e, "l", function () {
          return $;
        }),
        n.d(e, "e", function () {
          return S;
        }),
        n.d(e, "b", function () {
          return O;
        }),
        n.d(e, "s", function () {
          return E;
        }),
        n.d(e, "g", function () {
          return C;
        }),
        n.d(e, "h", function () {
          return k;
        }),
        n.d(e, "d", function () {
          return T;
        }),
        n.d(e, "r", function () {
          return A;
        }),
        n.d(e, "j", function () {
          return j;
        }),
        n.d(e, "t", function () {
          return P;
        }),
        n.d(e, "o", function () {
          return N;
        }),
        n.d(e, "q", function () {
          return I;
        }),
        n.d(e, "f", function () {
          return L;
        }),
        n.d(e, "c", function () {
          return V;
        }),
        n.d(e, "i", function () {
          return U;
        }),
        n.d(e, "p", function () {
          return M;
        }),
        n.d(e, "a", function () {
          return Y;
        }),
        n.d(e, "v", function () {
          return z;
        }),
        n.d(e, "n", function () {
          return J;
        }),
        n.d(e, "u", function () {
          return X;
        }));
      var r = n(26),
        o = n(5),
        c = n(17),
        l = n(21),
        d =
          (n(27),
          n(45),
          n(28),
          n(77),
          n(79),
          n(20),
          n(46),
          n(121),
          n(53),
          n(54),
          n(22),
          n(80),
          n(202),
          n(35),
          n(36),
          n(30),
          n(29),
          n(14),
          n(269),
          n(19),
          n(39),
          n(52),
          n(40),
          n(203),
          n(61),
          n(81),
          n(97),
          n(47),
          n(1)),
        _ = n.n(d),
        h = n(43);
      function f(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function v(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? f(Object(n), !0).forEach(function (e) {
                Object(c.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : f(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      function m(t, e) {
        var n =
          ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
          t["@@iterator"];
        if (!n) {
          if (
            Array.isArray(t) ||
            (n = (function (t, a) {
              if (t) {
                if ("string" == typeof t) return x(t, a);
                var e = {}.toString.call(t).slice(8, -1);
                return (
                  "Object" === e && t.constructor && (e = t.constructor.name),
                  "Map" === e || "Set" === e
                    ? Array.from(t)
                    : "Arguments" === e ||
                        /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                      ? x(t, a)
                      : void 0
                );
              }
            })(t)) ||
            (e && t && "number" == typeof t.length)
          ) {
            n && (t = n);
            var r = 0,
              o = function () {};
            return {
              s: o,
              n: function () {
                return r >= t.length
                  ? { done: !0 }
                  : { done: !1, value: t[r++] };
              },
              e: function (t) {
                throw t;
              },
              f: o,
            };
          }
          throw new TypeError(
            "Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.",
          );
        }
        var c,
          a = !0,
          u = !1;
        return {
          s: function () {
            n = n.call(t);
          },
          n: function () {
            var t = n.next();
            return ((a = t.done), t);
          },
          e: function (t) {
            ((u = !0), (c = t));
          },
          f: function () {
            try {
              a || null == n.return || n.return();
            } finally {
              if (u) throw c;
            }
          },
        };
      }
      function x(t, a) {
        (null == a || a > t.length) && (a = t.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
        return n;
      }
      function y(t) {
        _.a.config.errorHandler && _.a.config.errorHandler(t);
      }
      function w(t) {
        return t.then(function (t) {
          return t.default || t;
        });
      }
      function $(t) {
        return (
          t.$options &&
          "function" == typeof t.$options.fetch &&
          !t.$options.fetch.length
        );
      }
      function S(t) {
        var e,
          n =
            arguments.length > 1 && void 0 !== arguments[1] ? arguments[1] : [],
          r = m(t.$children || []);
        try {
          for (r.s(); !(e = r.n()).done; ) {
            var o = e.value;
            (o.$fetch && n.push(o), o.$children && S(o, n));
          }
        } catch (t) {
          r.e(t);
        } finally {
          r.f();
        }
        return n;
      }
      function O(t, e) {
        if (e || !t.options.__hasNuxtData) {
          var n =
            t.options._originDataFn ||
            t.options.data ||
            function () {
              return {};
            };
          ((t.options._originDataFn = n),
            (t.options.data = function () {
              var data = n.call(this, this);
              return (
                this.$ssrContext && (e = this.$ssrContext.asyncData[t.cid]),
                v(v({}, data), e)
              );
            }),
            (t.options.__hasNuxtData = !0),
            t._Ctor &&
              t._Ctor.options &&
              (t._Ctor.options.data = t.options.data));
        }
      }
      function E(t) {
        return (
          (t.options && t._Ctor === t) ||
            (t.options
              ? ((t._Ctor = t), (t.extendOptions = t.options))
              : ((t = _.a.extend(t))._Ctor = t),
            !t.options.name &&
              t.options.__file &&
              (t.options.name = t.options.__file)),
          t
        );
      }
      function C(t) {
        var e = arguments.length > 1 && void 0 !== arguments[1] && arguments[1],
          n =
            arguments.length > 2 && void 0 !== arguments[2]
              ? arguments[2]
              : "components";
        return Array.prototype.concat.apply(
          [],
          t.matched.map(function (t, r) {
            return Object.keys(t[n]).map(function (o) {
              return (e && e.push(r), t[n][o]);
            });
          }),
        );
      }
      function k(t) {
        return C(
          t,
          arguments.length > 1 && void 0 !== arguments[1] && arguments[1],
          "instances",
        );
      }
      function T(t, e) {
        return Array.prototype.concat.apply(
          [],
          t.matched.map(function (t, n) {
            return Object.keys(t.components).reduce(function (r, o) {
              return (
                t.components[o]
                  ? r.push(e(t.components[o], t.instances[o], t, o, n))
                  : delete t.components[o],
                r
              );
            }, []);
          }),
        );
      }
      function A(t, e) {
        return Promise.all(
          T(
            t,
            (function () {
              var t = Object(o.a)(
                regeneratorRuntime.mark(function t(n, r, o, c) {
                  var l, d, _;
                  return regeneratorRuntime.wrap(
                    function (t) {
                      for (;;)
                        switch ((t.prev = t.next)) {
                          case 0:
                            if ("function" != typeof n || n.options) {
                              t.next = 4;
                              break;
                            }
                            return ((t.prev = 1), (t.next = 2), n());
                          case 2:
                            ((n = t.sent), (t.next = 4));
                            break;
                          case 3:
                            throw (
                              (t.prev = 3),
                              (_ = t.catch(1)) &&
                                "ChunkLoadError" === _.name &&
                                "undefined" != typeof window &&
                                window.sessionStorage &&
                                ((l = Date.now()),
                                (!(d = parseInt(
                                  window.sessionStorage.getItem("nuxt-reload"),
                                )) ||
                                  d + 6e4 < l) &&
                                  (window.sessionStorage.setItem(
                                    "nuxt-reload",
                                    l,
                                  ),
                                  window.location.reload(!0))),
                              _
                            );
                          case 4:
                            return (
                              (o.components[c] = n = E(n)),
                              t.abrupt(
                                "return",
                                "function" == typeof e ? e(n, r, o, c) : n,
                              )
                            );
                          case 5:
                          case "end":
                            return t.stop();
                        }
                    },
                    t,
                    null,
                    [[1, 3]],
                  );
                }),
              );
              return function (e, n, r, o) {
                return t.apply(this, arguments);
              };
            })(),
          ),
        );
      }
      function j(t) {
        return R.apply(this, arguments);
      }
      function R() {
        return (R = Object(o.a)(
          regeneratorRuntime.mark(function t(e) {
            return regeneratorRuntime.wrap(function (t) {
              for (;;)
                switch ((t.prev = t.next)) {
                  case 0:
                    if (e) {
                      t.next = 1;
                      break;
                    }
                    return t.abrupt("return");
                  case 1:
                    return ((t.next = 2), A(e));
                  case 2:
                    return t.abrupt(
                      "return",
                      v(
                        v({}, e),
                        {},
                        {
                          meta: C(e).map(function (t, n) {
                            return v(
                              v({}, t.options.meta),
                              (e.matched[n] || {}).meta,
                            );
                          }),
                        },
                      ),
                    );
                  case 3:
                  case "end":
                    return t.stop();
                }
            }, t);
          }),
        )).apply(this, arguments);
      }
      function P(t, e) {
        return D.apply(this, arguments);
      }
      function D() {
        return (D = Object(o.a)(
          regeneratorRuntime.mark(function t(e, n) {
            var o, c, d, _;
            return regeneratorRuntime.wrap(function (t) {
              for (;;)
                switch ((t.prev = t.next)) {
                  case 0:
                    return (
                      e.context ||
                        ((e.context = {
                          isStatic: !1,
                          isDev: !1,
                          isHMR: !1,
                          app: e,
                          payload: n.payload,
                          error: n.error,
                          base: e.router.options.base,
                          env: {
                            SEARCH_API_BASE_URL:
                              "https://search.htv-services.com/search",
                            CLIENT_CONTEXT_UNCACHED_API_BASE_URL:
                              "https://h.freeanimehentai.net",
                            CLIENT_CONTEXT_CACHED_API_BASE_URL:
                              "https://cached.freeanimehentai.net",
                            CLIENT_CONTEXT_UNCACHED_GROWTH_API_BASE_URL:
                              "https://community-uploads.highwinds-cdn.com",
                            CLIENT_CONTEXT_CACHED_GROWTH_API_BASE_URL:
                              "https://community-uploads.highwinds-cdn.com",
                          },
                        }),
                        n.req && (e.context.req = n.req),
                        n.res && (e.context.res = n.res),
                        n.ssrContext && (e.context.ssrContext = n.ssrContext),
                        (e.context.redirect = function (t, path, n) {
                          if (t) {
                            e.context._redirected = !0;
                            var o = Object(r.a)(path);
                            if (
                              ("number" == typeof t ||
                                ("undefined" !== o && "object" !== o) ||
                                ((n = path || {}),
                                (path = t),
                                (o = Object(r.a)(path)),
                                (t = 302)),
                              "object" === o &&
                                (path = e.router.resolve(path).route.fullPath),
                              !/(^[.]{1,2}\/)|(^\/(?!\/))/.test(path))
                            )
                              throw (
                                (path = Object(h.d)(path, n)),
                                window.location.assign(path),
                                new Error("ERR_REDIRECT")
                              );
                            e.context.next({ path: path, query: n, status: t });
                          }
                        }),
                        (e.context.nuxtState = window.__NUXT__)),
                      (t.next = 1),
                      Promise.all([j(n.route), j(n.from)])
                    );
                  case 1:
                    ((o = t.sent),
                      (c = Object(l.a)(o, 2)),
                      (d = c[0]),
                      (_ = c[1]),
                      n.route && (e.context.route = d),
                      n.from && (e.context.from = _),
                      n.error && (e.context.error = n.error),
                      (e.context.next = n.next),
                      (e.context._redirected = !1),
                      (e.context._errored = !1),
                      (e.context.isHMR = !1),
                      (e.context.params = e.context.route.params || {}),
                      (e.context.query = e.context.route.query || {}));
                  case 2:
                  case "end":
                    return t.stop();
                }
            }, t);
          }),
        )).apply(this, arguments);
      }
      function N(t, e, n) {
        return !t.length || e._redirected || e._errored || (n && n.aborted)
          ? Promise.resolve()
          : I(t[0], e).then(function () {
              return N(t.slice(1), e, n);
            });
      }
      function I(t, e) {
        var n;
        return (n =
          2 === t.length
            ? new Promise(function (n) {
                t(e, function (t, data) {
                  (t && e.error(t), n((data = data || {})));
                });
              })
            : t(e)) &&
          n instanceof Promise &&
          "function" == typeof n.then
          ? n
          : Promise.resolve(n);
      }
      function L(base, t) {
        if ("hash" === t) return window.location.hash.replace(/^#\//, "");
        base = decodeURI(base).slice(0, -1);
        var path = decodeURI(window.location.pathname);
        base && path.startsWith(base) && (path = path.slice(base.length));
        var e = (path || "/") + window.location.search + window.location.hash;
        return Object(h.c)(e);
      }
      function V(t, e) {
        return (function (t, e) {
          for (var n = new Array(t.length), i = 0; i < t.length; i++)
            "object" === Object(r.a)(t[i]) &&
              (n[i] = new RegExp("^(?:" + t[i].pattern + ")$", K(e)));
          return function (e, r) {
            for (
              var path = "",
                data = e || {},
                o = (r || {}).pretty ? H : encodeURIComponent,
                c = 0;
              c < t.length;
              c++
            ) {
              var l = t[c];
              if ("string" != typeof l) {
                var d = data[l.name || "pathMatch"],
                  _ = void 0;
                if (null == d) {
                  if (l.optional) {
                    l.partial && (path += l.prefix);
                    continue;
                  }
                  throw new TypeError(
                    'Expected "' + l.name + '" to be defined',
                  );
                }
                if (Array.isArray(d)) {
                  if (!l.repeat)
                    throw new TypeError(
                      'Expected "' +
                        l.name +
                        '" to not repeat, but received `' +
                        JSON.stringify(d) +
                        "`",
                    );
                  if (0 === d.length) {
                    if (l.optional) continue;
                    throw new TypeError(
                      'Expected "' + l.name + '" to not be empty',
                    );
                  }
                  for (var h = 0; h < d.length; h++) {
                    if (((_ = o(d[h])), !n[c].test(_)))
                      throw new TypeError(
                        'Expected all "' +
                          l.name +
                          '" to match "' +
                          l.pattern +
                          '", but received `' +
                          JSON.stringify(_) +
                          "`",
                      );
                    path += (0 === h ? l.prefix : l.delimiter) + _;
                  }
                } else {
                  if (((_ = l.asterisk ? F(d) : o(d)), !n[c].test(_)))
                    throw new TypeError(
                      'Expected "' +
                        l.name +
                        '" to match "' +
                        l.pattern +
                        '", but received "' +
                        _ +
                        '"',
                    );
                  path += l.prefix + _;
                }
              } else path += l;
            }
            return path;
          };
        })(
          (function (t, e) {
            var n,
              r = [],
              o = 0,
              c = 0,
              path = "",
              l = (e && e.delimiter) || "/";
            for (; null != (n = B.exec(t)); ) {
              var d = n[0],
                _ = n[1],
                h = n.index;
              if (((path += t.slice(c, h)), (c = h + d.length), _))
                path += _[1];
              else {
                var f = t[c],
                  v = n[2],
                  m = n[3],
                  x = n[4],
                  y = n[5],
                  w = n[6],
                  $ = n[7];
                path && (r.push(path), (path = ""));
                var S = null != v && null != f && f !== v,
                  O = "+" === w || "*" === w,
                  E = "?" === w || "*" === w,
                  C = n[2] || l,
                  pattern = x || y;
                r.push({
                  name: m || o++,
                  prefix: v || "",
                  delimiter: C,
                  optional: E,
                  repeat: O,
                  partial: S,
                  asterisk: Boolean($),
                  pattern: pattern
                    ? W(pattern)
                    : $
                      ? ".*"
                      : "[^" + G(C) + "]+?",
                });
              }
            }
            c < t.length && (path += t.substr(c));
            path && r.push(path);
            return r;
          })(t, e),
          e,
        );
      }
      function U(t, e) {
        var n = {},
          r = v(v({}, t), e);
        for (var o in r) String(t[o]) !== String(e[o]) && (n[o] = !0);
        return n;
      }
      function M(t) {
        var e;
        if (t.message || "string" == typeof t) e = t.message || t;
        else
          try {
            e = JSON.stringify(t, null, 2);
          } catch (n) {
            e = "[".concat(t.constructor.name, "]");
          }
        return v(
          v({}, t),
          {},
          {
            message: e,
            statusCode:
              t.statusCode ||
              t.status ||
              (t.response && t.response.status) ||
              500,
          },
        );
      }
      ((window.onNuxtReadyCbs = []),
        (window.onNuxtReady = function (t) {
          window.onNuxtReadyCbs.push(t);
        }));
      var B = new RegExp(
        [
          "(\\\\.)",
          "([\\/.])?(?:(?:\\:(\\w+)(?:\\(((?:\\\\.|[^\\\\()])+)\\))?|\\(((?:\\\\.|[^\\\\()])+)\\))([+*?])?|(\\*))",
        ].join("|"),
        "g",
      );
      function H(t, e) {
        var n = e ? /[?#]/g : /[/?#]/g;
        return encodeURI(t).replace(n, function (t) {
          return "%" + t.charCodeAt(0).toString(16).toUpperCase();
        });
      }
      function F(t) {
        return H(t, !0);
      }
      function G(t) {
        return t.replace(/([.+*?=^!:${}()[\]|/\\])/g, "\\$1");
      }
      function W(t) {
        return t.replace(/([=!:$/()])/g, "\\$1");
      }
      function K(t) {
        return t && t.sensitive ? "" : "i";
      }
      function Y(t, e, n) {
        (t.$options[e] || (t.$options[e] = []),
          t.$options[e].includes(n) || t.$options[e].push(n));
      }
      var z = h.b,
        J = (h.e, h.a);
      function X(t) {
        try {
          window.history.scrollRestoration = t;
        } catch (t) {}
      }
    },
    1: function (t, e) {
      t.exports = Vue;
    },
    120: function (t, e, n) {
      "use strict";
      (n(28),
        n(77),
        n(79),
        n(20),
        n(80),
        n(46),
        n(53),
        n(54),
        n(22),
        n(14),
        n(19),
        n(39),
        n(52),
        n(40),
        n(30),
        n(47),
        n(62));
      var r = n(1),
        o = n.n(r);
      function c(t, e) {
        var n =
          ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
          t["@@iterator"];
        if (!n) {
          if (
            Array.isArray(t) ||
            (n = (function (t, a) {
              if (t) {
                if ("string" == typeof t) return l(t, a);
                var e = {}.toString.call(t).slice(8, -1);
                return (
                  "Object" === e && t.constructor && (e = t.constructor.name),
                  "Map" === e || "Set" === e
                    ? Array.from(t)
                    : "Arguments" === e ||
                        /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                      ? l(t, a)
                      : void 0
                );
              }
            })(t)) ||
            (e && t && "number" == typeof t.length)
          ) {
            n && (t = n);
            var r = 0,
              o = function () {};
            return {
              s: o,
              n: function () {
                return r >= t.length
                  ? { done: !0 }
                  : { done: !1, value: t[r++] };
              },
              e: function (t) {
                throw t;
              },
              f: o,
            };
          }
          throw new TypeError(
            "Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.",
          );
        }
        var c,
          a = !0,
          u = !1;
        return {
          s: function () {
            n = n.call(t);
          },
          n: function () {
            var t = n.next();
            return ((a = t.done), t);
          },
          e: function (t) {
            ((u = !0), (c = t));
          },
          f: function () {
            try {
              a || null == n.return || n.return();
            } finally {
              if (u) throw c;
            }
          },
        };
      }
      function l(t, a) {
        (null == a || a > t.length) && (a = t.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
        return n;
      }
      var d =
          window.requestIdleCallback ||
          function (t) {
            var e = Date.now();
            return setTimeout(function () {
              t({
                didTimeout: !1,
                timeRemaining: function () {
                  return Math.max(0, 50 - (Date.now() - e));
                },
              });
            }, 1);
          },
        _ =
          window.cancelIdleCallback ||
          function (t) {
            clearTimeout(t);
          },
        h =
          window.IntersectionObserver &&
          new window.IntersectionObserver(function (t) {
            t.forEach(function (t) {
              var e = t.intersectionRatio,
                link = t.target;
              e <= 0 || !link.__prefetch || link.__prefetch();
            });
          });
      e.a = {
        name: "NuxtLink",
        extends: o.a.component("RouterLink"),
        props: {
          prefetch: { type: Boolean, default: !0 },
          noPrefetch: { type: Boolean, default: !1 },
        },
        mounted: function () {
          this.prefetch &&
            !this.noPrefetch &&
            (this.handleId = d(this.observe, { timeout: 2e3 }));
        },
        beforeDestroy: function () {
          (_(this.handleId),
            this.__observed &&
              (h.unobserve(this.$el), delete this.$el.__prefetch));
        },
        methods: {
          observe: function () {
            h &&
              this.shouldPrefetch() &&
              ((this.$el.__prefetch = this.prefetchLink.bind(this)),
              h.observe(this.$el),
              (this.__observed = !0));
          },
          shouldPrefetch: function () {
            return this.getPrefetchComponents().length > 0;
          },
          canPrefetch: function () {
            var t = navigator.connection;
            return !(
              this.$nuxt.isOffline ||
              (t && ((t.effectiveType || "").includes("2g") || t.saveData))
            );
          },
          getPrefetchComponents: function () {
            return this.$router
              .resolve(this.to, this.$route, this.append)
              .resolved.matched.map(function (t) {
                return t.components.default;
              })
              .filter(function (t) {
                return "function" == typeof t && !t.options && !t.__prefetched;
              });
          },
          prefetchLink: function () {
            if (this.canPrefetch()) {
              h.unobserve(this.$el);
              var t,
                e = c(this.getPrefetchComponents());
              try {
                for (e.s(); !(t = e.n()).done; ) {
                  var n = t.value,
                    r = n();
                  (r instanceof Promise && r.catch(function () {}),
                    (n.__prefetched = !0));
                }
              } catch (t) {
                e.e(t);
              } finally {
                e.f();
              }
            }
          },
        },
      };
    },
    155: function (t, e, n) {
      "use strict";
      var r = {};
      ((r.interad = n(267)),
        (r.interad = r.interad.default || r.interad),
        (e.a = r));
    },
    157: function (t, e) {
      t.exports = VueRouter;
    },
    158: function (t, e, n) {
      var r = n(297),
        o = n(300);
      function c(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function l(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? c(Object(n), !0).forEach(function (e) {
                r(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : c(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      (n(27), n(28), n(20), n(46), n(35), n(36), n(29), n(14), n(161), n(30));
      var d = ("undefined" != typeof window ? window.Vue : n(1)).ref;
      t.exports = function (t, e) {
        var n = d({});
        var r = null;
        function c(t) {
          return _.apply(this, arguments);
        }
        function _() {
          return (_ = o(
            regeneratorRuntime.mark(function t(e) {
              var o, c, l, text, data, d;
              return regeneratorRuntime.wrap(
                function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        if (((o = e.key), !n.value[o])) {
                          t.next = 1;
                          break;
                        }
                        return t.abrupt("return");
                      case 1:
                        return (
                          o && (n.value[o] = !0),
                          (c = {
                            method: e.method || "GET",
                            signal: e.abort_controller_signal,
                            headers: r(e),
                          }),
                          e.payload &&
                            !["GET", "HEAD", "DELETE"].includes(
                              c.method.toUpperCase(),
                            ) &&
                            (c.body = JSON.stringify(e.payload)),
                          (t.prev = 2),
                          (t.next = 3),
                          fetch(e.url, c)
                        );
                      case 3:
                        if ((l = t.sent).ok) {
                          t.next = 4;
                          break;
                        }
                        return t.abrupt("return", Promise.reject(l));
                      case 4:
                        return ((t.next = 5), l.text());
                      case 5:
                        return (
                          "" !== (text = t.sent).trim() &&
                            (data = JSON.parse(text)),
                          t.abrupt(
                            "return",
                            Promise.resolve({ response: l, data: data }),
                          )
                        );
                      case 6:
                        return (
                          (t.prev = 6),
                          (d = t.catch(2)),
                          t.abrupt("return", Promise.reject(d))
                        );
                      case 7:
                        return (
                          (t.prev = 7),
                          o && (n.value[o] = !1),
                          t.finish(7)
                        );
                      case 8:
                      case "end":
                        return t.stop();
                    }
                },
                t,
                null,
                [[2, 6, 7, 8]],
              );
            }),
          )).apply(this, arguments);
        }
        function h() {
          return (
            (h = o(
              regeneratorRuntime.mark(function t(e) {
                var n,
                  r = arguments;
                return regeneratorRuntime.wrap(function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        return (
                          (n = r.length > 1 && void 0 !== r[1] ? r[1] : {}),
                          (t.next = 1),
                          c(l({ method: "GET", url: e }, n))
                        );
                      case 1:
                        return t.abrupt("return", t.sent);
                      case 2:
                      case "end":
                        return t.stop();
                    }
                }, t);
              }),
            )),
            h.apply(this, arguments)
          );
        }
        function f() {
          return (
            (f = o(
              regeneratorRuntime.mark(function t(e) {
                var n,
                  r,
                  o = arguments;
                return regeneratorRuntime.wrap(function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        return (
                          (n = o.length > 1 && void 0 !== o[1] ? o[1] : {}),
                          (r = o.length > 2 && void 0 !== o[2] ? o[2] : {}),
                          (t.next = 1),
                          c(l({ method: "POST", url: e, payload: n }, r))
                        );
                      case 1:
                        return t.abrupt("return", t.sent);
                      case 2:
                      case "end":
                        return t.stop();
                    }
                }, t);
              }),
            )),
            f.apply(this, arguments)
          );
        }
        function v() {
          return (
            (v = o(
              regeneratorRuntime.mark(function t(e) {
                var n,
                  r,
                  o = arguments;
                return regeneratorRuntime.wrap(function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        return (
                          (n = o.length > 1 && void 0 !== o[1] ? o[1] : {}),
                          (r = o.length > 2 && void 0 !== o[2] ? o[2] : {}),
                          (t.next = 1),
                          c(l({ method: "PUT", url: e, payload: n }, r))
                        );
                      case 1:
                        return t.abrupt("return", t.sent);
                      case 2:
                      case "end":
                        return t.stop();
                    }
                }, t);
              }),
            )),
            v.apply(this, arguments)
          );
        }
        function m() {
          return (
            (m = o(
              regeneratorRuntime.mark(function t(e) {
                var n,
                  r = arguments;
                return regeneratorRuntime.wrap(function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        return (
                          (n = r.length > 1 && void 0 !== r[1] ? r[1] : {}),
                          (t.next = 1),
                          c(l({ method: "DELETE", url: e }, n))
                        );
                      case 1:
                        return t.abrupt("return", t.sent);
                      case 2:
                      case "end":
                        return t.stop();
                    }
                }, t);
              }),
            )),
            m.apply(this, arguments)
          );
        }
        ((window.Emit = function (t) {
          return window.dispatchEvent(new CustomEvent(t));
        }),
          (r = function (t) {
            var e, n, r;
            return (
              Emit("e"),
              {
                "content-type": "application/json",
                accept: "application/json",
                "x-session-token":
                  (null === (e = S) || void 0 === e
                    ? void 0
                    : e.session_token) || "",
                "x-user-license":
                  (null === (n = S) || void 0 === n
                    ? void 0
                    : n.encrypted_user_license) || "",
                "x-license": t.x_license || "",
                "x-signature-version": "web2",
                "x-signature": window.ssignature,
                "x-time": window.stime,
                "x-csrf-token":
                  (null === (r = S) || void 0 === r ? void 0 : r.csrf_token) ||
                  "",
              }
            );
          }),
          e("getApiBaseUrl", function () {
            return "growth" ===
              (arguments.length > 0 && void 0 !== arguments[0]
                ? arguments[0]
                : "default")
              ? (t.$S.user, "https://community-uploads.highwinds-cdn.com")
              : t.$S.user
                ? "https://h.freeanimehentai.net"
                : "https://cached.freeanimehentai.net";
          }),
          e("get", function (t) {
            return h.apply(this, arguments);
          }),
          e("post", function (t) {
            return f.apply(this, arguments);
          }),
          e("put", function (t) {
            return v.apply(this, arguments);
          }),
          e("del", function (t) {
            return m.apply(this, arguments);
          }));
      };
    },
    213: function (t, e, n) {
      t.exports = {};
    },
    220: function (t, e) {
      t.exports = Vuetify;
    },
    221: function (t, e) {
      t.exports = VueLazyload;
    },
    222: function (t, e, n) {
      "use strict";
      var r = n(5),
        o = (n(27), n(14), n(62), n(1)),
        c = n.n(o),
        l = n(0),
        d = window.__NUXT__;
      function _() {
        if (!this._hydrated) return this.$fetch();
      }
      function h() {
        if (
          (t = this).$vnode &&
          t.$vnode.elm &&
          t.$vnode.elm.dataset &&
          t.$vnode.elm.dataset.fetchKey
        ) {
          var t;
          ((this._hydrated = !0),
            (this._fetchKey = this.$vnode.elm.dataset.fetchKey));
          var data = d.fetch[this._fetchKey];
          if (data && data._error) this.$fetchState.error = data._error;
          else for (var e in data) c.a.set(this.$data, e, data[e]);
        }
      }
      function f() {
        var t = this;
        return (
          this._fetchPromise ||
            (this._fetchPromise = v.call(this).then(function () {
              delete t._fetchPromise;
            })),
          this._fetchPromise
        );
      }
      function v() {
        return m.apply(this, arguments);
      }
      function m() {
        return (m = Object(r.a)(
          regeneratorRuntime.mark(function t() {
            var e,
              n,
              r,
              o,
              c = this;
            return regeneratorRuntime.wrap(
              function (t) {
                for (;;)
                  switch ((t.prev = t.next)) {
                    case 0:
                      return (
                        this.$nuxt.nbFetching++,
                        (this.$fetchState.pending = !0),
                        (this.$fetchState.error = null),
                        (this._hydrated = !1),
                        (e = null),
                        (n = Date.now()),
                        (t.prev = 1),
                        (t.next = 2),
                        this.$options.fetch.call(this)
                      );
                    case 2:
                      t.next = 4;
                      break;
                    case 3:
                      ((t.prev = 3), (o = t.catch(1)), (e = Object(l.p)(o)));
                    case 4:
                      if (!((r = this._fetchDelay - (Date.now() - n)) > 0)) {
                        t.next = 5;
                        break;
                      }
                      return (
                        (t.next = 5),
                        new Promise(function (t) {
                          return setTimeout(t, r);
                        })
                      );
                    case 5:
                      ((this.$fetchState.error = e),
                        (this.$fetchState.pending = !1),
                        (this.$fetchState.timestamp = Date.now()),
                        this.$nextTick(function () {
                          return c.$nuxt.nbFetching--;
                        }));
                    case 6:
                    case "end":
                      return t.stop();
                  }
              },
              t,
              this,
              [[1, 3]],
            );
          }),
        )).apply(this, arguments);
      }
      e.a = {
        beforeCreate: function () {
          Object(l.l)(this) &&
            ((this._fetchDelay =
              "number" == typeof this.$options.fetchDelay
                ? this.$options.fetchDelay
                : 200),
            c.a.util.defineReactive(this, "$fetchState", {
              pending: !1,
              error: null,
              timestamp: Date.now(),
            }),
            (this.$fetch = f.bind(this)),
            Object(l.a)(this, "created", h),
            Object(l.a)(this, "beforeMount", _));
        },
      };
    },
    223: function (t, e, n) {
      t.exports = n(224);
    },
    224: function (t, e, n) {
      "use strict";
      (n.r(e),
        function (t) {
          var e = n(26),
            r = n(5),
            o =
              (n(123),
              n(231),
              n(243),
              n(244),
              n(27),
              n(28),
              n(77),
              n(79),
              n(45),
              n(20),
              n(80),
              n(46),
              n(53),
              n(54),
              n(22),
              n(29),
              n(14),
              n(19),
              n(39),
              n(52),
              n(40),
              n(256),
              n(262),
              n(30),
              n(47),
              n(62),
              n(1)),
            c = n.n(o),
            l = n(217),
            d = n(155),
            _ = n(0),
            h = n(44),
            f = n(222),
            v = n(120);
          function m(t, e) {
            var n =
              ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
              t["@@iterator"];
            if (!n) {
              if (
                Array.isArray(t) ||
                (n = (function (t, a) {
                  if (t) {
                    if ("string" == typeof t) return x(t, a);
                    var e = {}.toString.call(t).slice(8, -1);
                    return (
                      "Object" === e &&
                        t.constructor &&
                        (e = t.constructor.name),
                      "Map" === e || "Set" === e
                        ? Array.from(t)
                        : "Arguments" === e ||
                            /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                          ? x(t, a)
                          : void 0
                    );
                  }
                })(t)) ||
                (e && t && "number" == typeof t.length)
              ) {
                n && (t = n);
                var r = 0,
                  o = function () {};
                return {
                  s: o,
                  n: function () {
                    return r >= t.length
                      ? { done: !0 }
                      : { done: !1, value: t[r++] };
                  },
                  e: function (t) {
                    throw t;
                  },
                  f: o,
                };
              }
              throw new TypeError(
                "Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.",
              );
            }
            var c,
              a = !0,
              u = !1;
            return {
              s: function () {
                n = n.call(t);
              },
              n: function () {
                var t = n.next();
                return ((a = t.done), t);
              },
              e: function (t) {
                ((u = !0), (c = t));
              },
              f: function () {
                try {
                  a || null == n.return || n.return();
                } finally {
                  if (u) throw c;
                }
              },
            };
          }
          function x(t, a) {
            (null == a || a > t.length) && (a = t.length);
            for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
            return n;
          }
          (c.a.__nuxt__fetch__mixin__ ||
            (c.a.mixin(f.a), (c.a.__nuxt__fetch__mixin__ = !0)),
            c.a.component(v.a.name, v.a),
            c.a.component("NLink", v.a),
            t.fetch || (t.fetch = l.a));
          var y,
            w,
            $ = [],
            S = window.__NUXT__ || {},
            O = S.config || {};
          (O._app && (n.p = Object(_.v)(O._app.cdnURL, O._app.assetsPath)),
            Object.assign(c.a.config, { silent: !0, performance: !1 }));
          var E = c.a.config.errorHandler || console.error;
          function C(t, e, n) {
            for (
              var r = function (component) {
                  var t =
                    (function (component, t) {
                      if (
                        !component ||
                        !component.options ||
                        !component.options[t]
                      )
                        return {};
                      var option = component.options[t];
                      if ("function" == typeof option) {
                        for (
                          var e = arguments.length,
                            n = new Array(e > 2 ? e - 2 : 0),
                            r = 2;
                          r < e;
                          r++
                        )
                          n[r - 2] = arguments[r];
                        return option.apply(void 0, n);
                      }
                      return option;
                    })(component, "transition", e, n) || {};
                  return "string" == typeof t ? { name: t } : t;
                },
                o = n ? Object(_.g)(n) : [],
                c = Math.max(t.length, o.length),
                l = [],
                d = function () {
                  var e = Object.assign({}, r(t[i])),
                    n = Object.assign({}, r(o[i]));
                  (Object.keys(e)
                    .filter(function (t) {
                      return (
                        void 0 !== e[t] && !t.toLowerCase().includes("leave")
                      );
                    })
                    .forEach(function (t) {
                      n[t] = e[t];
                    }),
                    l.push(n));
                },
                i = 0;
              i < c;
              i++
            )
              d();
            return l;
          }
          function k(t, e, n) {
            return T.apply(this, arguments);
          }
          function T() {
            return (T = Object(r.a)(
              regeneratorRuntime.mark(function t(e, n, r) {
                var o,
                  c,
                  l,
                  d,
                  h,
                  f = this;
                return regeneratorRuntime.wrap(
                  function (t) {
                    for (;;)
                      switch ((t.prev = t.next)) {
                        case 0:
                          if (
                            ((this._routeChanged =
                              Boolean(y.nuxt.err) || n.name !== e.name),
                            (this._paramChanged =
                              !this._routeChanged && n.path !== e.path),
                            (this._queryChanged =
                              !this._paramChanged && n.fullPath !== e.fullPath),
                            (this._diffQuery = this._queryChanged
                              ? Object(_.i)(e.query, n.query)
                              : []),
                            (this._routeChanged || this._paramChanged) &&
                              this.$loading.start &&
                              !this.$loading.manual &&
                              this.$loading.start(),
                            (t.prev = 1),
                            !this._queryChanged)
                          ) {
                            t.next = 3;
                            break;
                          }
                          return (
                            (t.next = 2),
                            Object(_.r)(e, function (t, e) {
                              return { Component: t, instance: e };
                            })
                          );
                        case 2:
                          ((o = t.sent),
                            o.some(function (t) {
                              var r = t.Component,
                                o = t.instance,
                                c = r.options.watchQuery;
                              return (
                                !0 === c ||
                                (Array.isArray(c)
                                  ? c.some(function (t) {
                                      return f._diffQuery[t];
                                    })
                                  : "function" == typeof c &&
                                    c.apply(o, [e.query, n.query]))
                              );
                            }) &&
                              this.$loading.start &&
                              !this.$loading.manual &&
                              this.$loading.start());
                        case 3:
                          (r(), (t.next = 6));
                          break;
                        case 4:
                          if (
                            ((t.prev = 4),
                            (h = t.catch(1)),
                            (l =
                              (c = h || {}).statusCode ||
                              c.status ||
                              (c.response && c.response.status) ||
                              500),
                            (d = c.message || ""),
                            !/^Loading( CSS)? chunk (\d)+ failed\./.test(d))
                          ) {
                            t.next = 5;
                            break;
                          }
                          return (
                            window.location.reload(!0),
                            t.abrupt("return")
                          );
                        case 5:
                          (this.error({ statusCode: l, message: d }),
                            this.$nuxt.$emit("routeChanged", e, n, c),
                            r());
                        case 6:
                        case "end":
                          return t.stop();
                      }
                  },
                  t,
                  this,
                  [[1, 4]],
                );
              }),
            )).apply(this, arguments);
          }
          function A(t, e) {
            return (
              S.serverRendered && e && Object(_.b)(t, e),
              (t._Ctor = t),
              t
            );
          }
          function j(t) {
            return Object(_.d)(
              t,
              (function () {
                var t = Object(r.a)(
                  regeneratorRuntime.mark(function t(e, n, r, o, c) {
                    var l;
                    return regeneratorRuntime.wrap(function (t) {
                      for (;;)
                        switch ((t.prev = t.next)) {
                          case 0:
                            if ("function" != typeof e || e.options) {
                              t.next = 2;
                              break;
                            }
                            return ((t.next = 1), e());
                          case 1:
                            e = t.sent;
                          case 2:
                            return (
                              (l = A(
                                Object(_.s)(e),
                                S.data ? S.data[c] : null,
                              )),
                              (r.components[o] = l),
                              t.abrupt("return", l)
                            );
                          case 3:
                          case "end":
                            return t.stop();
                        }
                    }, t);
                  }),
                );
                return function (e, n, r, o, c) {
                  return t.apply(this, arguments);
                };
              })(),
            );
          }
          function R(t, e, n, r) {
            var o = this,
              c = [],
              l = !1;
            if (
              (void 0 !== n &&
                ((c = []),
                (n = Object(_.s)(n)).options.middleware &&
                  (c = c.concat(n.options.middleware)),
                t.forEach(function (t) {
                  t.options.middleware && (c = c.concat(t.options.middleware));
                })),
              (c = c.map(function (t) {
                return "function" == typeof t
                  ? t
                  : ("function" != typeof d.a[t] &&
                      ((l = !0),
                      o.error({
                        statusCode: 500,
                        message: "Unknown middleware " + t,
                      })),
                    d.a[t]);
              })),
              !l)
            )
              return Object(_.o)(c, e, r);
          }
          function P(t, e, n, r) {
            return D.apply(this, arguments);
          }
          function D() {
            return (
              (D = Object(r.a)(
                regeneratorRuntime.mark(function t(e, n, o, c) {
                  var l,
                    d,
                    f,
                    v,
                    x,
                    w,
                    S,
                    O,
                    E,
                    k,
                    T,
                    A,
                    j,
                    P,
                    D,
                    N,
                    I,
                    L,
                    V = this;
                  return regeneratorRuntime.wrap(
                    function (t) {
                      for (;;)
                        switch ((t.prev = t.next)) {
                          case 0:
                            if (
                              !1 !== this._routeChanged ||
                              !1 !== this._paramChanged ||
                              !1 !== this._queryChanged
                            ) {
                              t.next = 1;
                              break;
                            }
                            return t.abrupt("return", o());
                          case 1:
                            return (
                              e === n
                                ? (($ = []), !0)
                                : ((l = []),
                                  ($ = Object(_.g)(n, l).map(function (t, i) {
                                    return Object(_.c)(n.matched[l[i]].path)(
                                      n.params,
                                    );
                                  }))),
                              (d = !1),
                              (f = function (path) {
                                (n.path === path.path &&
                                  V.$loading.finish &&
                                  V.$loading.finish(),
                                  n.path !== path.path &&
                                    V.$loading.pause &&
                                    V.$loading.pause(),
                                  d || ((d = !0), o(path)));
                              }),
                              (t.next = 2),
                              Object(_.t)(y, {
                                route: e,
                                from: n,
                                error: function (t) {
                                  c.aborted || y.nuxt.error.call(V, t);
                                },
                                next: f.bind(this),
                              })
                            );
                          case 2:
                            if (
                              ((this._dateLastError = y.nuxt.dateErr),
                              (this._hadError = Boolean(y.nuxt.err)),
                              (v = []),
                              (x = Object(_.g)(e, v)).length)
                            ) {
                              t.next = 10;
                              break;
                            }
                            return (
                              (t.next = 3),
                              R.call(this, x, y.context, void 0, c)
                            );
                          case 3:
                            if (!d) {
                              t.next = 4;
                              break;
                            }
                            return t.abrupt("return");
                          case 4:
                            if (!c.aborted) {
                              t.next = 5;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 5:
                            return (
                              (w = (h.a.options || h.a).layout),
                              (t.next = 6),
                              this.loadLayout(
                                "function" == typeof w
                                  ? w.call(h.a, y.context)
                                  : w,
                              )
                            );
                          case 6:
                            return (
                              (S = t.sent),
                              (t.next = 7),
                              R.call(this, x, y.context, S, c)
                            );
                          case 7:
                            if (!d) {
                              t.next = 8;
                              break;
                            }
                            return t.abrupt("return");
                          case 8:
                            if (!c.aborted) {
                              t.next = 9;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 9:
                            return (
                              y.context.error({
                                statusCode: 404,
                                message: "This page could not be found",
                              }),
                              t.abrupt("return", o())
                            );
                          case 10:
                            return (
                              x.forEach(function (t) {
                                t._Ctor &&
                                  t._Ctor.options &&
                                  ((t.options.asyncData =
                                    t._Ctor.options.asyncData),
                                  (t.options.fetch = t._Ctor.options.fetch));
                              }),
                              this.setTransitions(C(x, e, n)),
                              (t.prev = 11),
                              (t.next = 12),
                              R.call(this, x, y.context, void 0, c)
                            );
                          case 12:
                            if (!d) {
                              t.next = 13;
                              break;
                            }
                            return t.abrupt("return");
                          case 13:
                            if (!c.aborted) {
                              t.next = 14;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 14:
                            if (!y.context._errored) {
                              t.next = 15;
                              break;
                            }
                            return t.abrupt("return", o());
                          case 15:
                            return (
                              "function" == typeof (O = x[0].options.layout) &&
                                (O = O(y.context)),
                              (t.next = 16),
                              this.loadLayout(O)
                            );
                          case 16:
                            return (
                              (O = t.sent),
                              (t.next = 17),
                              R.call(this, x, y.context, O, c)
                            );
                          case 17:
                            if (!d) {
                              t.next = 18;
                              break;
                            }
                            return t.abrupt("return");
                          case 18:
                            if (!c.aborted) {
                              t.next = 19;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 19:
                            if (!y.context._errored) {
                              t.next = 20;
                              break;
                            }
                            return t.abrupt("return", o());
                          case 20:
                            ((E = !0),
                              (t.prev = 21),
                              (k = m(x)),
                              (t.prev = 22),
                              k.s());
                          case 23:
                            if ((T = k.n()).done) {
                              t.next = 27;
                              break;
                            }
                            if (
                              "function" ==
                              typeof (A = T.value).options.validate
                            ) {
                              t.next = 24;
                              break;
                            }
                            return t.abrupt("continue", 26);
                          case 24:
                            return (
                              (t.next = 25),
                              A.options.validate(y.context)
                            );
                          case 25:
                            if ((E = t.sent)) {
                              t.next = 26;
                              break;
                            }
                            return t.abrupt("continue", 27);
                          case 26:
                            t.next = 23;
                            break;
                          case 27:
                            t.next = 29;
                            break;
                          case 28:
                            ((t.prev = 28), (N = t.catch(22)), k.e(N));
                          case 29:
                            return ((t.prev = 29), k.f(), t.finish(29));
                          case 30:
                            t.next = 32;
                            break;
                          case 31:
                            return (
                              (t.prev = 31),
                              (I = t.catch(21)),
                              this.error({
                                statusCode: I.statusCode || "500",
                                message: I.message,
                              }),
                              t.abrupt("return", o())
                            );
                          case 32:
                            if (E) {
                              t.next = 33;
                              break;
                            }
                            return (
                              this.error({
                                statusCode: 404,
                                message: "This page could not be found",
                              }),
                              t.abrupt("return", o())
                            );
                          case 33:
                            return (
                              (t.next = 34),
                              Promise.all(
                                x.map(
                                  (function () {
                                    var t = Object(r.a)(
                                      regeneratorRuntime.mark(function t(r, i) {
                                        var o, c, l, d, h, f, m, x, p;
                                        return regeneratorRuntime.wrap(
                                          function (t) {
                                            for (;;)
                                              switch ((t.prev = t.next)) {
                                                case 0:
                                                  if (
                                                    ((r._path = Object(_.c)(
                                                      e.matched[v[i]].path,
                                                    )(e.params)),
                                                    (r._dataRefresh = !1),
                                                    (o = r._path !== $[i]),
                                                    V._routeChanged && o
                                                      ? (r._dataRefresh = !0)
                                                      : V._paramChanged && o
                                                        ? ((c =
                                                            r.options
                                                              .watchParam),
                                                          (r._dataRefresh =
                                                            !1 !== c))
                                                        : V._queryChanged &&
                                                          (!0 ===
                                                          (l =
                                                            r.options
                                                              .watchQuery)
                                                            ? (r._dataRefresh =
                                                                !0)
                                                            : Array.isArray(l)
                                                              ? (r._dataRefresh =
                                                                  l.some(
                                                                    function (
                                                                      t,
                                                                    ) {
                                                                      return V
                                                                        ._diffQuery[
                                                                        t
                                                                      ];
                                                                    },
                                                                  ))
                                                              : "function" ==
                                                                  typeof l &&
                                                                (j ||
                                                                  (j = Object(
                                                                    _.h,
                                                                  )(e)),
                                                                (r._dataRefresh =
                                                                  l.apply(
                                                                    j[i],
                                                                    [
                                                                      e.query,
                                                                      n.query,
                                                                    ],
                                                                  )))),
                                                    V._hadError ||
                                                      !V._isMounted ||
                                                      r._dataRefresh)
                                                  ) {
                                                    t.next = 1;
                                                    break;
                                                  }
                                                  return t.abrupt("return");
                                                case 1:
                                                  return (
                                                    (d = []),
                                                    (h =
                                                      r.options.asyncData &&
                                                      "function" ==
                                                        typeof r.options
                                                          .asyncData),
                                                    (f =
                                                      Boolean(
                                                        r.options.fetch,
                                                      ) &&
                                                      r.options.fetch.length),
                                                    (m = h && f ? 30 : 45),
                                                    h &&
                                                      ((x = Object(_.q)(
                                                        r.options.asyncData,
                                                        y.context,
                                                      )).then(function (t) {
                                                        (Object(_.b)(r, t),
                                                          V.$loading.increase &&
                                                            V.$loading.increase(
                                                              m,
                                                            ));
                                                      }),
                                                      d.push(x)),
                                                    (V.$loading.manual =
                                                      !1 === r.options.loading),
                                                    f &&
                                                      (((p = r.options.fetch(
                                                        y.context,
                                                      )) &&
                                                        (p instanceof Promise ||
                                                          "function" ==
                                                            typeof p.then)) ||
                                                        (p =
                                                          Promise.resolve(p)),
                                                      p.then(function (t) {
                                                        V.$loading.increase &&
                                                          V.$loading.increase(
                                                            m,
                                                          );
                                                      }),
                                                      d.push(p)),
                                                    t.abrupt(
                                                      "return",
                                                      Promise.all(d),
                                                    )
                                                  );
                                                case 2:
                                                case "end":
                                                  return t.stop();
                                              }
                                          },
                                          t,
                                        );
                                      }),
                                    );
                                    return function (e, n) {
                                      return t.apply(this, arguments);
                                    };
                                  })(),
                                ),
                              )
                            );
                          case 34:
                            if (d) {
                              t.next = 36;
                              break;
                            }
                            if (
                              (this.$loading.finish &&
                                !this.$loading.manual &&
                                this.$loading.finish(),
                              !c.aborted)
                            ) {
                              t.next = 35;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 35:
                            o();
                          case 36:
                            t.next = 41;
                            break;
                          case 37:
                            if (
                              ((t.prev = 37), (L = t.catch(11)), !c.aborted)
                            ) {
                              t.next = 38;
                              break;
                            }
                            return (o(!1), t.abrupt("return"));
                          case 38:
                            if ("ERR_REDIRECT" !== (P = L || {}).message) {
                              t.next = 39;
                              break;
                            }
                            return t.abrupt(
                              "return",
                              this.$nuxt.$emit("routeChanged", e, n, P),
                            );
                          case 39:
                            return (
                              ($ = []),
                              Object(_.k)(P),
                              "function" ==
                                typeof (D = (h.a.options || h.a).layout) &&
                                (D = D(y.context)),
                              (t.next = 40),
                              this.loadLayout(D)
                            );
                          case 40:
                            (this.error(P),
                              this.$nuxt.$emit("routeChanged", e, n, P),
                              o());
                          case 41:
                          case "end":
                            return t.stop();
                        }
                    },
                    t,
                    this,
                    [
                      [11, 37],
                      [21, 31],
                      [22, 28, 29, 30],
                    ],
                  );
                }),
              )),
              D.apply(this, arguments)
            );
          }
          function N(t, n) {
            Object(_.d)(t, function (t, n, r, o) {
              return (
                "object" !== Object(e.a)(t) ||
                  t.options ||
                  (((t = c.a.extend(t))._Ctor = t), (r.components[o] = t)),
                t
              );
            });
          }
          Object(h.b)(null, S.config)
            .then(function (t) {
              return H.apply(this, arguments);
            })
            .catch(E);
          var I = new WeakMap();
          function L(t, e, n) {
            var r = Boolean(this.$options.nuxt.err);
            this._hadError &&
              this._dateLastError === this.$options.nuxt.dateErr &&
              (r = !1);
            var o = r
              ? (h.a.options || h.a).layout
              : t.matched[0].components.default.options.layout;
            ("function" == typeof o && (o = o(y.context)),
              I.set(t, o),
              n && n());
          }
          function V(t) {
            var e = I.get(t);
            (I.delete(t),
              this._hadError &&
                this._dateLastError === this.$options.nuxt.dateErr &&
                (this.$options.nuxt.err = null),
              this.setLayout(e));
          }
          function U(t) {
            t._hadError &&
              t._dateLastError === t.$options.nuxt.dateErr &&
              t.error();
          }
          function M(t, e) {
            var n = this;
            if (
              !1 !== this._routeChanged ||
              !1 !== this._paramChanged ||
              !1 !== this._queryChanged
            ) {
              var r = Object(_.h)(t),
                o = Object(_.g)(t),
                l = !1;
              c.a.nextTick(function () {
                (r.forEach(function (t, i) {
                  if (
                    t &&
                    !t._isDestroyed &&
                    t.constructor._dataRefresh &&
                    o[i] === t.constructor &&
                    !0 !== t.$vnode.data.keepAlive &&
                    "function" == typeof t.constructor.options.data
                  ) {
                    var e = t.constructor.options.data.call(t);
                    for (var n in e) c.a.set(t.$data, n, e[n]);
                    l = !0;
                  }
                }),
                  l &&
                    window.$nuxt.$nextTick(function () {
                      window.$nuxt.$emit("triggerScroll");
                    }),
                  U(n));
              });
            }
          }
          function B(t) {
            (window.onNuxtReadyCbs.forEach(function (e) {
              "function" == typeof e && e(t);
            }),
              "function" == typeof window._onNuxtLoaded &&
                window._onNuxtLoaded(t),
              w.afterEach(function (e, n) {
                c.a.nextTick(function () {
                  return t.$nuxt.$emit("routeChanged", e, n);
                });
              }));
          }
          function H() {
            return (H = Object(r.a)(
              regeneratorRuntime.mark(function t(e) {
                var n, r, o, l, d, h, f, v;
                return regeneratorRuntime.wrap(function (t) {
                  for (;;)
                    switch ((t.prev = t.next)) {
                      case 0:
                        return (
                          (y = e.app),
                          (w = e.router),
                          (n = new c.a(y)),
                          (r = S.layout || "default"),
                          (t.next = 1),
                          n.loadLayout(r)
                        );
                      case 1:
                        return (
                          n.setLayout(r),
                          (o = function () {
                            (n.$mount("#__nuxt"),
                              w.afterEach(N),
                              w.beforeResolve(L.bind(n)),
                              w.afterEach(V.bind(n)),
                              w.afterEach(M.bind(n)),
                              c.a.nextTick(function () {
                                B(n);
                              }));
                          }),
                          (t.next = 2),
                          Promise.all(j(y.context.route))
                        );
                      case 2:
                        if (
                          ((l = t.sent),
                          (n.setTransitions =
                            n.$options.nuxt.setTransitions.bind(n)),
                          l.length &&
                            (n.setTransitions(C(l, w.currentRoute)),
                            ($ = w.currentRoute.matched.map(function (t) {
                              return Object(_.c)(t.path)(w.currentRoute.params);
                            }))),
                          (n.$loading = {}),
                          S.error &&
                            (n.error(S.error), (n.nuxt.errPageReady = !0)),
                          w.beforeEach(k.bind(n)),
                          (d = null),
                          (h = P.bind(n)),
                          w.beforeEach(function (t, e, n) {
                            (d && (d.aborted = !0),
                              h(t, e, n, (d = { aborted: !1 })));
                          }),
                          !S.serverRendered ||
                            !Object(_.n)(S.routePath, n.context.route.path))
                        ) {
                          t.next = 3;
                          break;
                        }
                        return t.abrupt("return", o());
                      case 3:
                        return (
                          (f = function () {
                            (L.call(n, w.currentRoute),
                              V.call(n, w.currentRoute));
                          }),
                          (v = function () {
                            (N(w.currentRoute, w.currentRoute), f(), U(n), o());
                          }),
                          (t.next = 4),
                          new Promise(function (t) {
                            return setTimeout(t, 0);
                          })
                        );
                      case 4:
                        P.call(
                          n,
                          w.currentRoute,
                          w.currentRoute,
                          function (path) {
                            if (path) {
                              var t = w.afterEach(function (e, n) {
                                (t(), v());
                              });
                              w.push(path, void 0, function (t) {
                                t && E(t);
                              });
                            } else v();
                          },
                          { aborted: !1 },
                        );
                      case 5:
                      case "end":
                        return t.stop();
                    }
                }, t);
              }),
            )).apply(this, arguments);
          }
        }.call(this, n(98)));
    },
    267: function (t, e, n) {
      "use strict";
      n.r(e);
      (n(22), n(19));
      e.default = function (t) {
        if (
          "undefined" != typeof window &&
          !/bot|googlebot|crawler|spider|robot|crawling/i.test(
            navigator.userAgent,
          ) &&
          (!t.$S.user || !t.$S.user.is_able_to_access_premium) &&
          t.from &&
          "inter" != t.from.name
        ) {
          var e = t.$S.browser_width < 960 ? "in_m4" : "in_d4";
          "1" == Cookies.get(e) ||
            t.redirect("/inter?r=".concat(t.route.fullPath));
        }
      };
    },
    278: function (t, e, n) {
      "use strict";
      n(213);
    },
    285: function (t, e, n) {
      "use strict";
      (n.r(e),
        n.d(e, "newEvents", function () {
          return c;
        }));
      var r = n(1),
        o = n.n(r);
      function c(t) {
        var e = new o.a();
        return (
          (e.ONLOAD = "Events.ONLOAD"),
          (e.GOTO = "Events.GOTO"),
          (e.SPA_GOTO = "Events.SPA_GOTO"),
          (e.MPA_GOTO = "Events.MPA_GOTO"),
          (e.OPEN = "Events.OPEN"),
          (e.SELECT_HENTAI_VIDEO = "Events.SELECT_HENTAI_VIDEO"),
          (e.SNACK = "Events.SNACK"),
          (e.SNACK_ERR = "Events.SNACK_ERR"),
          (e.DO_SEARCH = "Events.DO_SEARCH"),
          (e.DO_NEW_SEARCH = "Events.DO_NEW_SEARCH"),
          (e.SAVE_USER_SEARCH_STATE = "Events.SAVE_USER_SEARCH_STATE"),
          (e.LOGGED_IN = "Events.LOGGED_IN"),
          (e.LOGGED_OUT = "Events.LOGGED_OUT"),
          (e.ROUTE_CHANGED = "Events.ROUTE_CHANGED"),
          (e.NAV_DRAWER = "Events.NAV_DRAWER"),
          (e.ACCOUNT_DIALOG = "Events.ACCOUNT_DIALOG"),
          (e.LOGIN = "Events.LOGIN"),
          (e.CREATE_ACCOUNT = "Events.CREATE_ACCOUNT"),
          (e.FORGOT_PASSWORD = "Events.FORGOT_PASSWORD"),
          (e.CONTACT_US_DIALOG = "Events.CONTACT_US_DIALOG"),
          (e.CONTACT_US_ALLOW_VIDEO_REPORT =
            "Events.CONTACT_US_ALLOW_VIDEO_REPORT"),
          (e.PLAYLIST_ITEM_REMOVED = "Events.PLAYLIST_ITEM_REMOVED"),
          (e.PLAYLIST_ITEM_ADDED = "Events.PLAYLIST_ITEM_ADDED"),
          (e.ACTIVATE_HENTAI_VIDEO_CARD_HORIZONTAL_MENU =
            "Events.ACTIVATE_HENTAI_VIDEO_CARD_HORIZONTAL_MENU"),
          (e.ACTIVATE_ADD_TO_PLAYLIST_MENU =
            "Events.ACTIVATE_ADD_TO_PLAYLIST_MENU"),
          (e.TOGGLE_SHUFFLE = "Events.TOGGLE_SHUFFLE"),
          (e.ACTIVATE_GENERAL_CONFIRMATION_DIALOG =
            "Events.ACTIVATE_GENERAL_CONFIRMATION_DIALOG"),
          (e.ACTIVATE_USER_COMMENT_MENU_AROUND =
            "Events.ACTIVATE_USER_COMMENT_MENU_AROUND"),
          (e.DELETE_USER_COMMENT = "Events.DELETE_USER_COMMENT"),
          (e.FOCUS_SEARCH_INPUT = "Events.FOCUS_SEARCH_INPUT"),
          (e.NEW_TAB = "Events.NEW_TAB"),
          (e.PLAY_NEXT_VIDEO = "Events.PLAY_NEXT_VIDEO"),
          e
        );
      }
    },
    286: function (t, n, r) {
      "use strict";
      (r.r(n),
        r.d(n, "newState", function () {
          return v;
        }));
      var o = r(17),
        c = r(5),
        l =
          (r(28),
          r(45),
          r(20),
          r(35),
          r(36),
          r(29),
          r(14),
          r(19),
          r(30),
          r(39),
          r(152),
          r(206),
          r(287),
          r(161),
          r(27),
          r(1)),
        d = r.n(l);
      function h(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function f(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? h(Object(n), !0).forEach(function (e) {
                Object(o.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : h(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      function v(t) {
        return new d.a({
          created: function () {
            this.$on("DECODE_VIDEOS_MANIFEST", this.decodeVideosManifest);
          },
          beforeDestroy: function () {
            this.$off("DECODE_VIDEOS_MANIFEST", this.decodeVideosManifest);
          },
          data: function () {
            return {
              scrollY: "undefined" == typeof window ? 0 : window.scrollY,
              csrf_token: null,
              csrf_token_last_fetched_time_unix: null,
              version: null,
              is_new_version: !1,
              r: null,
              country_code: null,
              page_name: "",
              user_agent: null,
              ip: null,
              referrer: null,
              geo: null,
              is_dev: !is_prod,
              is_wasm_supported: !0,
              is_mounted: !1,
              is_loading: !1,
              is_image_processing: !1,
              is_searching: !1,
              browser_width: 0,
              browser_height: 0,
              system_msg: "",
              data: {},
              auth_claim: null,
              session_token: null,
              session_token_expire_time_unix: null,
              env: null,
              user: null,
              user_setting: null,
              encrypted_user_license: null,
              playlists: null,
              shuffle: !1,
              account_dialog: {
                is_visible: !1,
                active_tab_id: t.$EVT.LOGIN,
                tabs: [
                  { id: t.$EVT.LOGIN, icon: "login-variant", title: "Sign In" },
                  {
                    id: t.$EVT.CREATE_ACCOUNT,
                    icon: "account-plus",
                    title: "Create Account",
                  },
                  {
                    id: t.$EVT.FORGOT_PASSWORD,
                    icon: "help",
                    title: "Forgot Password",
                  },
                ],
              },
              contact_us_dialog: {
                is_visible: !1,
                is_video_report: !1,
                subject: "",
                email: "",
                message: "",
                is_sent: !1,
              },
              general_confirmation_dialog: {
                is_visible: !1,
                is_persistent: !1,
                is_mini_close_button_visible: !0,
                is_cancel_button_visible: !0,
                cancel_button_text: "Cancel",
                title: "",
                body: "",
                confirm_button_text: "Confirm",
                confirmation_callback: null,
              },
              snackbar: {
                timeout: 5e3,
                context: "secondary",
                mode: "",
                y: "bottom",
                x: "left",
                is_visible: !1,
                text: "",
              },
              search: {
                cache_sorting_config: [
                  "_score",
                  { created_at_unix: { order: "desc" } },
                ],
                search_text: "",
                search_response_payload: null,
                total_search_results_count: 0,
                order_by: "created_at",
                ordering: "desc",
                tags_match: "all",
                page_size: 24,
                offset: 0,
                page: 1,
                number_of_pages: 0,
                tags: [],
                active_tags_count: 0,
                brands: [],
                active_brands_count: 0,
                blacklisted_tags: [],
                active_blacklisted_tags_count: 0,
                is_using_preferences: !0,
              },
            };
          },
          methods: {
            doFetch: function (n, r, o) {
              var l = arguments;
              return Object(c.a)(
                regeneratorRuntime.mark(function c() {
                  var d, _, h, f, data, v, m, x, y, w, $;
                  return regeneratorRuntime.wrap(
                    function (c) {
                      for (;;)
                        switch ((c.prev = c.next)) {
                          case 0:
                            ((d =
                              l.length > 3 && void 0 !== l[3] ? l[3] : "sync"),
                              o.req,
                              o.route,
                              (_ = o.beforeNuxtRender),
                              (h = o.$S),
                              (c.next = 5));
                            break;
                          case 2:
                            ((v = c.sent),
                              (f = v.response),
                              (data = v.data),
                              (h.data = {}),
                              h.processFetchData(data, r, h),
                              (c.next = 4));
                            break;
                          case 3:
                            throw ((c.prev = 3), c.catch(1));
                          case 4:
                            return (
                              _(function (t) {
                                t.nuxtState.state = h._data;
                              }),
                              (m =
                                f.headers && f.headers.get
                                  ? f.headers.get("cache-control")
                                  : null),
                              (x =
                                f.headers && f.headers.get
                                  ? f.headers.get("vary")
                                  : null),
                              m && o.res.setHeader("cache-control", m),
                              x && o.res.setHeader("vary", x),
                              c.abrupt("return", data)
                            );
                          case 5:
                            if ("sync" != d) {
                              c.next = 10;
                              break;
                            }
                            return ((c.prev = 6), (c.next = 7), o.$get(n));
                          case 7:
                            ((y = c.sent),
                              (w = y.data),
                              t.$S.processFetchData(w, r, t.$S),
                              t.$S.$emit(
                                "".concat(r, "_FETCH_SUCCESS"),
                                t.$S.data[r],
                              ),
                              (c.next = 9));
                            break;
                          case 8:
                            throw (
                              (c.prev = 8),
                              ($ = c.catch(6)),
                              e("[client] doFetch sync error:"),
                              e($),
                              $
                            );
                          case 9:
                            c.next = 11;
                            break;
                          case 10:
                            "async" == d &&
                              o
                                .$get(n)
                                .then(function (t) {
                                  var data = t.data;
                                  (h.processFetchData(data, r, h),
                                    h.$emit(
                                      "".concat(r, "_FETCH_SUCCESS"),
                                      h.data[r],
                                    ));
                                })
                                .catch(function (t) {
                                  throw (
                                    e("[client] doFetch async error:"),
                                    e(t),
                                    t
                                  );
                                });
                          case 11:
                          case "end":
                            return c.stop();
                        }
                    },
                    c,
                    null,
                    [
                      [1, 3],
                      [6, 8],
                    ],
                  );
                }),
              )();
            },
            processFetchData: function (data, t, e) {
              d.a.set(e.data, t, data);
              [
                "session_token",
                "session_token_expire_time_unix",
                "env",
                "user",
                "user_setting",
                "encrypted_user_license",
                "playlists",
              ].forEach(function (n) {
                ((e[n] = data[n]), delete e.data[t][n]);
              });
            },
            timeAgo: function (t, e) {
              if (!t || !e) return "";
              var n = t - e;
              if (n <= 6e4) return "a few seconds ago";
              if (n <= 36e5) {
                var r = parseInt(n / 6e4);
                return r < 2 ? "a minute ago" : "".concat(r, " minutes ago");
              }
              if (n <= 864e5) {
                var o = parseInt(n / 36e5);
                return o < 2 ? "an hour ago" : "".concat(o, " hours ago");
              }
              if (n <= 6048e5) {
                var c = parseInt(n / 864e5);
                return c < 2 ? "yesterday" : "".concat(c, " days ago");
              }
              if (n <= 2592e6) {
                var l = parseInt(n / 6048e5);
                return l < 2 ? "last week" : "".concat(l, " weeks ago");
              }
              var d = parseInt(n / 2592e6),
                _ = "months";
              return (
                d < 2 && (_ = "month"),
                "".concat(d, " ").concat(_, " ago")
              );
            },
            isValidEmail: function (t) {
              return /^(([^<>()\[\]\\.,;:\s@"]+(\.[^<>()\[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/.test(
                String(t).toLowerCase(),
              );
            },
            confirm: function (e) {
              t.$S.general_confirmation_dialog = f(
                f(f({}, t.$App.general_confirmation_dialog_defaults), e),
                {},
                { is_visible: !0 },
              );
            },
            alert: function (e) {
              t.$S.general_confirmation_dialog =
                "string" == typeof e
                  ? f(
                      f({}, t.$App.general_confirmation_dialog_defaults),
                      {},
                      {
                        is_cancel_button_visible: !1,
                        title: "Notification",
                        body: e,
                        confirm_button_text: "OK",
                        is_visible: !0,
                        confirmation_callback: function () {
                          t.$S.general_confirmation_dialog.is_visible = !1;
                        },
                      },
                    )
                  : f(
                      f(f({}, t.$App.general_confirmation_dialog_defaults), e),
                      {},
                      { is_visible: !0 },
                    );
            },
            guid: function () {
              function t() {
                return Math.floor(65536 * (1 + Math.random()))
                  .toString(16)
                  .substring(1);
              }
              return (
                t() +
                t() +
                "-" +
                t() +
                "-" +
                t() +
                "-" +
                t() +
                "-" +
                t() +
                t() +
                t()
              );
            },
            randomString: function () {
              var t =
                  arguments.length > 0 && void 0 !== arguments[0]
                    ? arguments[0]
                    : 8,
                text = "",
                e = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
                n =
                  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
              text = e.charAt(Math.floor(52 * Math.random()));
              for (var i = 1; i < t; i++)
                text += n.charAt(Math.floor(62 * Math.random()));
              return text;
            },
            dateHuman: function (t) {
              var e =
                  arguments.length > 1 && void 0 !== arguments[1]
                    ? arguments[1]
                    : "yyyy-mm-dd",
                n = null;
              if (
                ((n = isNaN(t) ? new Date(t) : new Date(1e3 * parseInt(t))),
                "yyyy-mm-dd" == e)
              )
                return ""
                  .concat(n.getFullYear(), "-")
                  .concat((n.getMonth() + 1).toString().padStart(2, "0"), "-")
                  .concat(n.getDate().toString().padStart(2, "0"));
              return ""
                .concat(
                  [
                    "January",
                    "February",
                    "March",
                    "April",
                    "May",
                    "June",
                    "July",
                    "August",
                    "September",
                    "October",
                    "November",
                    "December",
                  ][n.getMonth()],
                  " ",
                )
                .concat(n.getDate(), ", ")
                .concat(n.getFullYear());
            },
            getOffset: function (t) {
              for (
                var e = 0, n = 0;
                t && !isNaN(t.offsetLeft) && !isNaN(t.offsetTop);
              )
                ((e += t.offsetLeft - t.scrollLeft),
                  (n += t.offsetTop - t.scrollTop),
                  (t = t.offsetParent));
              return { top: n, left: e };
            },
            getAndroidVersion: function () {
              var t = navigator.userAgent
                .toLowerCase()
                .match(/android\s([0-9\.]*)/);
              return !!t && t[1];
            },
            getIOSVersion: function () {
              if (/iP(hone|od|ad)/.test(navigator.platform)) {
                var t = navigator.appVersion.match(/OS (\d+)_(\d+)_?(\d+)?/);
                return [
                  parseInt(t[1], 10),
                  parseInt(t[2], 10),
                  parseInt(t[3] || 0, 10),
                ];
              }
            },
            getMacOSXVersion: function () {
              if (/MacIntel/.test(navigator.platform)) {
                if (/Firefox/.test(navigator.userAgent)) {
                  var t = navigator.userAgent.match(
                    /OS X (\d+)\.(\d+)\.?(\d+)?/,
                  );
                  return [
                    parseInt(t[1], 10),
                    parseInt(t[2], 10),
                    parseInt(t[3] || 0, 10),
                  ];
                }
                t = navigator.userAgent.match(/OS X (\d+)_(\d+)_?(\d+)?/);
                return [
                  parseInt(t[1], 10),
                  parseInt(t[2], 10),
                  parseInt(t[3] || 0, 10),
                ];
              }
            },
            openContactUsDialog: function () {
              t.$S.$emit("OPEN", "CONTACT_US_DIALOG");
            },
            getKeyChars: function () {
              return window.key;
            },
            encryptedHexToString: function (e) {
              try {
                var n = t.$S.getKeyChars(),
                  r = [];
                _.each(n.split(""), function (t) {
                  r.push(t.charCodeAt(0));
                });
                var o = aesjs.utils.hex.toBytes(e),
                  c = new aesjs.ModeOfOperation.cbc(r, window.iv).decrypt(o);
                return aesjs.utils.utf8.fromBytes(c).trim();
              } catch (t) {
                return "";
              }
            },
            decodeVideosManifest: function (e) {
              e && (e.is_wrapped || t.$S.$emit("VIDEOS_MANIFEST_DECODED"));
            },
            save: function (e, n) {
              t.$App.is_local_storage_available && localStorage.setItem(e, n);
            },
            load: function (e) {
              if (t.$App.is_local_storage_available)
                return localStorage.getItem(e);
            },
            onWindowMessage: function (e) {
              var n = e.origin;
              ("https://hanime.tv" == n ||
                n.endsWith(".hanime.tv") ||
                n.endsWith("freeanimehentai.net") ||
                n.endsWith(".highwinds-cdn.com") ||
                n.endsWith(".space-cdn.com") ||
                t.$S.is_dev) &&
                ("PLAY_NEXT_VIDEO" == e.data
                  ? t.$EVT.$emit(t.$EVT.PLAY_NEXT_VIDEO)
                  : "PLAYER_ON_POSTER_CLICK" == e.data
                    ? t.$EVT.$emit("PLAYER_ON_POSTER_CLICK", e)
                    : "OPEN_VIDEO_SETTINGS" == e.data
                      ? t.$EVT.$emit("OPEN_VIDEO_SETTINGS", e)
                      : "PLAYER_ON_PREROLL_AD_FAILURE" == e.data
                        ? t.$EVT.$emit("PLAYER_ON_PREROLL_AD_FAILURE", e)
                        : "PLAYER_REQUESTS_VIDEOS_MANIFEST" == e.data
                          ? t.$EVT.$emit("PLAYER_REQUESTS_VIDEOS_MANIFEST", e)
                          : "PREMIUM_ALERT" == e.data
                            ? t.$EVT.$emit("PREMIUM_ALERT", e)
                            : "PLAYER_REQUESTS_TOGGLE_FULL_BROWSER_SCREEN" ==
                                e.data
                              ? t.$EVT.$emit(
                                  "PLAYER_REQUESTS_TOGGLE_FULL_BROWSER_SCREEN",
                                  e,
                                )
                              : "PREROLL_ENDED_CONTENT_RESUMED" == e.data &&
                                t.$EVT.$emit(
                                  "PREROLL_ENDED_CONTENT_RESUMED",
                                  e,
                                ));
            },
          },
        });
      }
    },
    291: function (t, n, r) {
      "use strict";
      (r.r(n),
        r.d(n, "toggleHentaiVideoInPlaylist", function () {
          return v;
        }),
        r.d(n, "updatePlaylist", function () {
          return x;
        }));
      var o = r(17),
        c = r(63),
        d = r(5);
      (r(27),
        r(28),
        r(45),
        r(20),
        r(159),
        r(160),
        r(35),
        r(36),
        r(29),
        r(14),
        r(30));
      function h(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function f(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? h(Object(n), !0).forEach(function (e) {
                Object(o.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : h(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      function v(t, e, n, r, o) {
        return m.apply(this, arguments);
      }
      function m() {
        return (m = Object(d.a)(
          regeneratorRuntime.mark(function t(e, n, r, o, d) {
            var h, f, v, m, x, y, w, $, S;
            return regeneratorRuntime.wrap(
              function (t) {
                for (;;)
                  switch ((t.prev = t.next)) {
                    case 0:
                      if (!e.is_loading) {
                        t.next = 1;
                        break;
                      }
                      return t.abrupt("return");
                    case 1:
                      if (
                        ((e.is_loading = !0),
                        (h = null),
                        (f = null),
                        r.is_mutable
                          ? ((h = "".concat(
                              d.$getApiBaseUrl(),
                              "/api/v8/playlist_hentai_videos",
                            )),
                            (f = _.find(o, {
                              playlist_id: r.id,
                              hentai_video_id: n,
                            })))
                          : "Watch Later" == r.title
                            ? ((h = "".concat(
                                d.$getApiBaseUrl(),
                                "/rapi/v7/watch_later_playlist_hentai_videos",
                              )),
                              (f = _.find(o, {
                                playlist_id: r.id,
                                hentai_video_id: n,
                              })))
                            : "Liked Videos" == r.title &&
                              ((h = "".concat(
                                d.$getApiBaseUrl(),
                                "/rapi/v7/like_dislike_playlist_hentai_videos",
                              )),
                              (f = _.find(o, {
                                playlist_id: r.id,
                                hentai_video_id: n,
                              }))),
                        !f)
                      ) {
                        t.next = 6;
                        break;
                      }
                      return (
                        (t.prev = 2),
                        (t.next = 3),
                        d.$del("".concat(h, "/").concat(f.id))
                      );
                    case 3:
                      ((v = t.sent),
                        v.data,
                        (m = _.reject(o, {
                          playlist_id: r.id,
                          hentai_video_id: n,
                        })),
                        o.splice.apply(o, [0, o.length].concat(Object(c.a)(m))),
                        r.count--,
                        d.$EVT.$emit(d.$EVT.PLAYLIST_ITEM_REMOVED, n, r, o),
                        (e.is_loading = !1),
                        d.$EVT.$emit(d.$EVT.SNACK, "Removed from playlist."),
                        (t.next = 5));
                      break;
                    case 4:
                      ((t.prev = 4),
                        ($ = t.catch(2)),
                        (e.is_loading = !1),
                        l("ERROR"),
                        l($),
                        d.$EVT.$emit(
                          d.$EVT.SNACK_ERR,
                          "Unable to remove video from playlist.  Try again later.",
                        ));
                    case 5:
                      t.next = 9;
                      break;
                    case 6:
                      return (
                        (t.prev = 6),
                        (t.next = 7),
                        d.$post(h, { playlist_id: r.id, hentai_video_id: n })
                      );
                    case 7:
                      ((x = t.sent),
                        (y = x.data),
                        (w = y),
                        o.push(w),
                        r.count++,
                        d.$EVT.$emit(d.$EVT.PLAYLIST_ITEM_ADDED, n, r, o),
                        (e.is_loading = !1),
                        d.$EVT.$emit(d.$EVT.SNACK, "Added to playlist."),
                        (t.next = 9));
                      break;
                    case 8:
                      ((t.prev = 8),
                        (S = t.catch(6)),
                        (e.is_loading = !1),
                        S.response && S.response.data && S.response.data.errors
                          ? (l(S.response.data.errors[0]),
                            d.$EVT.$emit(
                              d.$EVT.SNACK_ERR,
                              S.response.data.errors[0],
                            ))
                          : (l(S),
                            d.$EVT.$emit(
                              d.$EVT.SNACK_ERR,
                              "Unable to add video to playlist.  Try again later.",
                            )));
                    case 9:
                    case "end":
                      return t.stop();
                  }
              },
              t,
              null,
              [
                [2, 4],
                [6, 8],
              ],
            );
          }),
        )).apply(this, arguments);
      }
      function x(t, e, n, r, o) {
        return y.apply(this, arguments);
      }
      function y() {
        return (y = Object(d.a)(
          regeneratorRuntime.mark(function t(n, r, o, c, l) {
            var d;
            return regeneratorRuntime.wrap(
              function (t) {
                for (;;)
                  switch ((t.prev = t.next)) {
                    case 0:
                      if (!n.is_loading) {
                        t.next = 1;
                        break;
                      }
                      return t.abrupt("return");
                    case 1:
                      return (
                        (n.is_loading = !0),
                        (t.prev = 2),
                        (t.next = 3),
                        l.$put(
                          ""
                            .concat(l.$getApiBaseUrl(), "/rapi/v7/playlists/")
                            .concat(r.id),
                          c,
                        )
                      );
                    case 3:
                      (t.sent,
                        (r = f(f({}, r), c)),
                        Object.assign(n.playlists[o], r),
                        l.$EVT.$emit(l.$EVT.SNACK, "Playlist updated."),
                        (t.next = 5));
                      break;
                    case 4:
                      ((t.prev = 4),
                        (d = t.catch(2)),
                        e(d),
                        l.$EVT.$emit(
                          l.$EVT.SNACK_ERR,
                          "Unable to update playlist.  Unknown error.",
                        ));
                    case 5:
                      n.is_loading = !1;
                    case 6:
                    case "end":
                      return t.stop();
                  }
              },
              t,
              null,
              [[2, 4]],
            );
          }),
        )).apply(this, arguments);
      }
    },
    292: function (t, e, n) {
      "use strict";
      (n.r(e),
        n.d(e, "newAppSingleton", function () {
          return f;
        }));
      (n(28), n(20), n(35), n(36), n(29), n(30));
      var r = n(17),
        o =
          (n(45),
          n(46),
          n(22),
          n(14),
          n(19),
          n(39),
          n(152),
          n(52),
          n(40),
          n(61),
          n(97),
          n(154),
          n(47),
          n(214),
          n(215),
          n(153),
          n(1)),
        c = n.n(o);
      function l(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function d(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? l(Object(n), !0).forEach(function (e) {
                Object(r.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : l(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      var _ = "https://discord.gg/hanime-tv",
        h = {
          site_loaded_at: null,
          discord_url: _,
          _doSearch: null,
          _updateBrowserDimensions: null,
          user_agent: null,
          is_bot: !1,
          is_android: !1,
          is_ios: !1,
          is_other: !1,
          is_safari: !1,
          is_edge: !1,
          is_mac: !1,
          is_local_storage_available: !1,
          stime_poll: null,
          nav: {
            toolbar: {
              class: "elevation-0 transparent",
              is_search_bar_focused: !1,
              is_search_menu_visible: !1,
            },
            drawer: { is_visible: !1, mini_variant: !1 },
            main_items: [
              { icon: "mdi-home", title: "Home", to: "/" },
              { icon: "mdi-fire", title: "Trending", to: "/browse/trending" },
              {
                icon: "mdi-shuffle-variant",
                title: "Random",
                to: "/browse/random",
              },
              {
                icon: "mdi-image-filter-hdr",
                title: "Images",
                to: "/browse/images",
              },
              { icon: "mdi-magnify", title: "Search", to: "/search" },
              { icon: "mdi-chart-bubble", title: "Browse", to: "/browse" },
              { icon: "mdi-apps", title: "Apps", to: "/apps" },
            ],
            channel_items: [],
            account_items: [
              { icon: "mdi-account-box", title: "My channel", to: "/channel" },
              { icon: "mdi-settings", title: "Settings", to: "/account" },
              { icon: "mdi-apps", title: "Download Apps", to: "/apps" },
              {
                icon: "mdi-discord",
                title: "Join our Fans' Discord",
                to: _,
                is_special_action: !0,
                is_external_link: !0,
              },
              {
                icon: "mdi-account-switch",
                title: "Switch account",
                to: "/sign-in",
                is_special_action: !0,
              },
              {
                icon: "mdi-power",
                title: "Sign out",
                to: "/signout",
                is_special_action: !0,
              },
            ],
            guest_items: [
              {
                icon: "mdi-login-variant",
                title: "Sign In",
                to: "/sign-in",
                is_special_action: !0,
              },
              {
                icon: "mdi-account-plus",
                title: "Create Account",
                to: "/create-account",
                is_special_action: !0,
              },
            ],
            connect_items: [
              {
                icon: "mdi-message-alert",
                title: "Contact Us",
                to: "/feedback",
                is_special_action: !0,
              },
              {
                icon: "mdi-discord",
                title: "hanime.tv Fans' Community",
                to: _,
                is_special_action: !1,
                is_external_link: !0,
              },
            ],
            resources_items: [
              { icon: "mdi-crown", title: "Premium Benefits", to: "/premium" },
              {
                icon: "mdi-palette-advanced",
                title: "Advertising",
                to: "/business",
              },
              { icon: "mdi-gavel", title: "Terms of Use", to: "/terms-of-use" },
              { icon: "mdi-file-document-box", title: "EULA", to: "/eula" },
              { icon: "mdi-verified", title: "2257 Statement", to: "/2257" },
              { icon: "mdi-guy-fawkes-mask", title: "Privacy", to: "/privacy" },
              { icon: "mdi-cookie", title: "Cookies Policy", to: "/cookies" },
            ],
            partner_items: [
              {
                icon: "mdi-discord",
                title: "hanime.tv Fans' Community",
                to: _,
                is_special_action: !1,
                is_external_link: !0,
              },
            ],
          },
          general_confirmation_dialog_defaults: {
            is_visible: !1,
            is_persistent: !1,
            is_mini_close_button_visible: !0,
            is_cancel_button_visible: !0,
            cancel_button_text: "Cancel",
            title: "",
            body: "",
            confirm_button_text: "Confirm",
            confirmation_callback: null,
          },
          statically_allowed_kinds: [
            "community-uploaded-image",
            "playlist-thumbnail-small",
            "playlist-thumbnail-large",
            "playlist-thumbnail-variable-size",
            "cps",
            "avatar",
            "channel-master-header",
          ],
        };
      function f(t) {
        return (
          (window.ctx = t),
          new c.a({
            data: d({}, h),
            methods: {
              login: function (data) {
                var e =
                  !(arguments.length > 1 && void 0 !== arguments[1]) ||
                  arguments[1];
                (this.saveState(data),
                  e &&
                    ("channel" == t.route.name ||
                      "account" == t.route.name ||
                      (t.$S.user &&
                        t.$S.user.is_able_to_access_premium &&
                        ("index" == t.route.name ||
                          "hentai-videos-id" == t.route.name ||
                          "videos-hentai-id" == t.route.name))) &&
                    window.location.reload(!0));
              },
              logout: function () {
                (this.saveState({}), (window.top.location.href = "/home"));
              },
              saveState: function (data) {
                var e = data.session_token;
                ((void 0 !== e && e && "undefined" != e && "null" != e) ||
                  (e = ""),
                  (t.$S.env = data.env),
                  (t.$S.session_token = e),
                  (t.$S.session_token_expire_time_unix =
                    data.session_token_expire_time_unix),
                  (t.$S.user = data.user),
                  (t.$S.user_setting = data.user_setting),
                  (t.$S.playlists = data.playlists),
                  t.$S.user_setting && t.$S.user_setting.primary_color
                    ? (($nuxt.$vuetify.theme.primary =
                        t.$S.user_setting.primary_color),
                      ($nuxt.$vuetify.theme.accent =
                        t.$S.user_setting.primary_color))
                    : (($nuxt.$vuetify.theme.primary =
                        globalThis.default_color),
                      ($nuxt.$vuetify.theme.accent = globalThis.default_color)),
                  Cookies.set("htv3session", t.$S.session_token, {
                    path: "/",
                    domain: window.cookie_domain,
                    expires: new Date(
                      1e3 * t.$S.session_token_expire_time_unix,
                    ),
                  }),
                  t.$S.session_token
                    ? (t.$EVT.$emit(
                        t.$EVT.SNACK,
                        "You are now logged in as ".concat(t.$S.user.name),
                      ),
                      t.$EVT.$emit(t.$EVT.LOGGED_IN))
                    : (Cookies.remove("htv3session", {
                        path: "/",
                        domain: window.cookie_domain,
                      }),
                      t.$EVT.$emit(
                        t.$EVT.SNACK,
                        "You have successfully logged out.",
                      ),
                      t.$EVT.$emit(t.$EVT.LOGGED_OUT)));
              },
              directUrl: function (t) {
                if (!t || 0 == t.length) return "";
                var u;
                try {
                  u = new URL(t);
                } catch (t) {
                  return "";
                }
                if (u.hostname.endsWith(".pages.dev"))
                  return (
                    (u.hostname = "hanime-cdn.com"),
                    (u.protocol = "https:"),
                    u.toString()
                  );
                if (
                  t.indexOf("hanime-cdn.com/") > -1 ||
                  t.indexOf(".imgur.com/") > -1
                )
                  return t;
                var path = this.getPath(t);
                if (/\/archived-assets-\d+\./.test(t)) {
                  var e = t.replace(/http[s]:\/\//, "").split("/")[0];
                  return "http://".concat(e).concat(path);
                }
                return "http://da.imageg.top".concat(path);
              },
              staticallyUrl: function (e) {
                var u,
                  n =
                    arguments.length > 1 && void 0 !== arguments[1]
                      ? arguments[1]
                      : null,
                  r =
                    arguments.length > 2 && void 0 !== arguments[2]
                      ? arguments[2]
                      : "default",
                  q =
                    arguments.length > 3 && void 0 !== arguments[3]
                      ? arguments[3]
                      : null;
                try {
                  u = new URL(e);
                } catch (t) {
                  return "";
                }
                if (u.hostname.endsWith(".pages.dev"))
                  return (
                    (u.hostname = "hanime-cdn.com"),
                    (u.protocol = "https:"),
                    u.toString()
                  );
                if (
                  e.includes("hanime-cdn.com/") ||
                  e.includes(".freeanimehentai.net/") ||
                  e.includes(".imgur.com/") ||
                  e.includes(".statically.io/")
                )
                  return e;
                if (t.$App.statically_allowed_kinds.includes(r)) {
                  var o = e.replace(/http(s)*:\/\//, ""),
                    c = o.split("/"),
                    l = c[0],
                    path = o.replace(l, ""),
                    d = c[c.length - 1],
                    _ = n ? ",h=".concat(n) : "",
                    h = "100";
                  if (q) h = q;
                  else if (d.includes(".")) {
                    var f = d.split(".");
                    h = "gif" == f[f.length - 1].toLowerCase() ? "80" : "91";
                  } else h = "91";
                  return "https://cdn.statically.io/img/"
                    .concat(l, "/f=auto")
                    .concat(_, ",q=")
                    .concat(h)
                    .concat(path);
                }
                return "";
              },
              jetpackUrl: function (t) {
                var u,
                  e =
                    arguments.length > 1 && void 0 !== arguments[1]
                      ? arguments[1]
                      : 100,
                  n =
                    arguments.length > 3 && void 0 !== arguments[3]
                      ? arguments[3]
                      : null,
                  r =
                    arguments.length > 4 && void 0 !== arguments[4]
                      ? arguments[4]
                      : "default";
                if (!t || 0 == t.length) return "";
                try {
                  u = new URL(t);
                } catch (t) {
                  return "";
                }
                if (u.hostname.endsWith(".pages.dev"))
                  return (
                    (u.hostname = "hanime-cdn.com"),
                    (u.protocol = "https:"),
                    u.toString()
                  );
                if (
                  t.indexOf("hanime-cdn.com/") > -1 ||
                  t.indexOf(".freeanimehentai.net/") > -1 ||
                  t.indexOf(".imgur.com/") > -1
                )
                  return t;
                var path = this.getPath(t),
                  o = "";
                if (
                  ((o = n
                    ? ""
                        .concat(path, "?quality=")
                        .concat(e, "&h=")
                        .concat(n, "&cachebust=1")
                    : "".concat(path, "?quality=").concat(e, "&cachebust=1")),
                  /\/archived-assets-\d+\./.test(t))
                ) {
                  var c = t
                    .replace(/http[s]:\/\//, "")
                    .split("/")[0]
                    .split(".")[0]
                    .split("-")
                    .pop();
                  return "https://archived-assets-"
                    .concat(c, ".imageg.top")
                    .concat(o);
                }
                if ("cps" == r) {
                  var l = [
                    "https://ba.alphafish.top".concat(o),
                    "https://ba.apperoni.top".concat(o),
                    "https://ba.balley.top".concat(o),
                  ];
                  return l[Math.floor(Math.random() * l.length)];
                }
                return "cps-no-random" == r
                  ? "https://ba.alphafish.top".concat(o)
                  : "playlist-banner" == r
                    ? "https://da.picial.top".concat(o)
                    : "apbcb" == r
                      ? "https://dynamic-assets.mobilius.top".concat(path)
                      : "https://wp.apperoni.top".concat(o);
              },
              getPath: function (t) {
                if (!t || 0 == t.length) return "";
                if (t.startsWith("/")) return t;
                var e = t
                    .replace(/http[s]{0,1}:\/\//, "")
                    .replace(/i\d\.wp\.com\//, ""),
                  n = e.split("/")[0];
                return e.replace(n, "");
              },
            },
          })
        );
      }
    },
    295: function (t, n, r) {
      "use strict";
      (r.r(n),
        function (t) {
          (r.d(n, "doCacheBust", function () {
            return l;
          }),
            r.d(n, "isSPAStale", function () {
              return _;
            }));
          var o = r(5),
            c =
              (r(27),
              r(14),
              r(39),
              r(40),
              r(47),
              r(214),
              r(215),
              r(153),
              new Date().getTime());
          function l(t) {
            return d.apply(this, arguments);
          }
          function d() {
            return (
              (d = Object(o.a)(
                regeneratorRuntime.mark(function e(n) {
                  var r,
                    o = arguments;
                  return regeneratorRuntime.wrap(function (e) {
                    for (;;)
                      switch ((e.prev = e.next)) {
                        case 0:
                          if (
                            ((r =
                              o.length > 1 && void 0 !== o[1] ? o[1] : null),
                            !(new Date().getTime() - c < 18e4) || !t.prod)
                          ) {
                            e.next = 1;
                            break;
                          }
                          return e.abrupt("return");
                        case 1:
                          return ((e.next = 2), _(n, r));
                        case 2:
                          if (!e.sent) {
                            e.next = 3;
                            break;
                          }
                          n.$S.alert({
                            is_persistent: !0,
                            is_mini_close_button_visible: !1,
                            is_cancel_button_visible: !1,
                            title: "Update!",
                            body: "The website just updated with some changes.  You need to reload this page to get the latest updates.",
                            confirm_button_text: "UPDATE & RELOAD",
                            confirmation_callback: function () {
                              var t = new URL(window.location.href);
                              (t.searchParams.set("_", n.$S.version),
                                (window.location.href = t.toString()));
                            },
                          });
                        case 3:
                        case "end":
                          return e.stop();
                      }
                  }, e);
                }),
              )),
              d.apply(this, arguments)
            );
          }
          function _(t) {
            return h.apply(this, arguments);
          }
          function h() {
            return (
              (h = Object(o.a)(
                regeneratorRuntime.mark(function t(n) {
                  var r,
                    o,
                    data,
                    c,
                    l = arguments;
                  return regeneratorRuntime.wrap(
                    function (t) {
                      for (;;)
                        switch ((t.prev = t.next)) {
                          case 0:
                            return (
                              (r =
                                l.length > 1 && void 0 !== l[1] ? l[1] : null),
                              (t.prev = 1),
                              (t.next = 2),
                              n.$get(
                                n.$config.ENV_JSON_URL +
                                  (r ? "?v=".concat(r) : ""),
                              )
                            );
                          case 2:
                            if (((o = t.sent), (data = o.data), n.$S.version)) {
                              t.next = 3;
                              break;
                            }
                            return (
                              (n.$S.version = data.vhtv2_version),
                              t.abrupt("return", !1)
                            );
                          case 3:
                            if (!(n.$S.version < data.vhtv2_version)) {
                              t.next = 4;
                              break;
                            }
                            return (
                              (n.$S.version = data.vhtv2_version),
                              t.abrupt("return", !0)
                            );
                          case 4:
                            return (
                              (n.$S.version = data.vhtv2_version),
                              t.abrupt("return", !1)
                            );
                          case 5:
                            t.next = 7;
                            break;
                          case 6:
                            return (
                              (t.prev = 6),
                              (c = t.catch(1)),
                              e(c),
                              t.abrupt("return", !1)
                            );
                          case 7:
                          case "end":
                            return t.stop();
                        }
                    },
                    t,
                    null,
                    [[1, 6]],
                  );
                }),
              )),
              h.apply(this, arguments)
            );
          }
        }.call(this, r(296)));
    },
    301: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = {
          components: {},
          beforeCreate: function () {},
          data: function () {
            return {};
          },
          methods: {},
          computed: {},
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this;
            t._self._c;
            return t.$S.is_mounted ? t._m(0) : t._e();
          },
          [
            function () {
              var t = this,
                e = t._self._c;
              return e("footer", { staticClass: "footer" }, [
                e(
                  "div",
                  { staticClass: "footer__main" },
                  [
                    e(
                      "v-flex",
                      { staticClass: "footer__column footer__column--logo" },
                      [
                        e(
                          "router-link",
                          {
                            staticClass: "footer__logo-link",
                            attrs: { to: "/" },
                          },
                          [
                            e("img", {
                              attrs: {
                                src: "https://hanime-cdn.com/images/logo-100.png",
                                alt: "hanime.tv footer logo",
                              },
                            }),
                          ],
                        ),
                      ],
                      1,
                    ),
                    t._v(" "),
                    e("v-flex", { staticClass: "footer__column" }, [
                      e("h4", { staticClass: "footer__header pb-3" }, [
                        t._v("hanime"),
                        e("span", { staticClass: "highlight" }, [t._v(".")]),
                        t._v("tv"),
                      ]),
                      t._v(" "),
                      e(
                        "ul",
                        { staticClass: "footer__list" },
                        t._l(t.$App.nav.main_items, function (n, i) {
                          return e(
                            "li",
                            {
                              key: "footer-nav-".concat(i),
                              staticClass: "footer__item",
                            },
                            [
                              e(
                                "router-link",
                                {
                                  staticClass: "footer__link",
                                  attrs: { to: n.to },
                                },
                                [t._v(t._s(n.title))],
                              ),
                            ],
                            1,
                          );
                        }),
                        0,
                      ),
                    ]),
                    t._v(" "),
                    e("v-flex", { staticClass: "footer__column" }, [
                      e("h4", { staticClass: "footer__header pb-3" }, [
                        t._v("Partners"),
                      ]),
                      t._v(" "),
                      e("ul", { staticClass: "footer__list" }, [
                        e("li", { staticClass: "footer__item" }, [
                          e(
                            "a",
                            {
                              staticClass: "footer__link",
                              attrs: {
                                target: "_blank",
                                href: "https://a.adtng.com/get/10002373",
                                rel: "noopener",
                              },
                            },
                            [t._v("Nutaku")],
                          ),
                        ]),
                        t._v(" "),
                        e("li", { staticClass: "footer__item" }, [
                          e(
                            "a",
                            {
                              staticClass: "footer__link",
                              attrs: {
                                target: "_blank",
                                href: "https://theporndude.com/",
                                rel: "noopener",
                              },
                            },
                            [t._v("ThePornDude")],
                          ),
                        ]),
                        t._v(" "),
                        t.$App.nav.partner_items.length > 0
                          ? e("li", { staticClass: "footer__item" }, [
                              e(
                                "a",
                                {
                                  staticClass: "footer__link",
                                  attrs: {
                                    target: "_blank",
                                    href: t.$App.nav.partner_items[0].to,
                                    rel: "noopener",
                                  },
                                },
                                [t._v(t._s(t.$App.nav.partner_items[0].title))],
                              ),
                            ])
                          : t._e(),
                      ]),
                    ]),
                    t._v(" "),
                    e("v-flex", { staticClass: "footer__column" }, [
                      e("h4", { staticClass: "footer__header pb-3" }, [
                        t._v("Resources"),
                      ]),
                      t._v(" "),
                      e(
                        "ul",
                        { staticClass: "footer__list" },
                        t._l(t.$App.nav.resources_items, function (n, i) {
                          return e(
                            "li",
                            {
                              key: "footer-resource-".concat(i),
                              staticClass: "footer__item",
                            },
                            [
                              e(
                                "a",
                                {
                                  staticClass: "footer__link",
                                  attrs: { href: n.to },
                                  on: {
                                    click: function (e) {
                                      return (
                                        e.preventDefault(),
                                        t.$EVT.$emit(t.$EVT.GOTO, n.to)
                                      );
                                    },
                                  },
                                },
                                [t._v(t._s(n.title))],
                              ),
                            ],
                          );
                        }),
                        0,
                      ),
                    ]),
                    t._v(" "),
                    e("v-flex", { staticClass: "footer__column" }, [
                      e("h4", { staticClass: "footer__header pb-3" }, [
                        t._v("Connect"),
                      ]),
                      t._v(" "),
                      e(
                        "ul",
                        { staticClass: "footer__list" },
                        t._l(t.$App.nav.connect_items, function (n, i) {
                          return e(
                            "li",
                            {
                              key: "footer-connect-".concat(i),
                              staticClass: "footer__item",
                            },
                            [
                              n.is_special_action
                                ? e(
                                    "div",
                                    {
                                      staticClass: "footer__link",
                                      on: {
                                        click: function (e) {
                                          return (
                                            e.preventDefault(),
                                            e.stopPropagation(),
                                            t.$EVT.$emit(t.$EVT.GOTO, n.to)
                                          );
                                        },
                                        mouseup: function (e) {
                                          return "button" in e && 1 !== e.button
                                            ? null
                                            : (e.preventDefault(),
                                              e.stopPropagation(),
                                              t.$EVT.$emit(t.$EVT.GOTO, n.to));
                                        },
                                      },
                                    },
                                    [
                                      e(
                                        "v-icon",
                                        { staticClass: "footer__icon" },
                                        [t._v(t._s(n.icon))],
                                      ),
                                      t._v(
                                        "\n            " +
                                          t._s(n.title) +
                                          "\n          ",
                                      ),
                                    ],
                                    1,
                                  )
                                : n.is_external_link
                                  ? e(
                                      "a",
                                      {
                                        staticClass: "footer__link",
                                        attrs: {
                                          href: n.to,
                                          target: "_blank",
                                          rel: "noopener",
                                        },
                                      },
                                      [
                                        e(
                                          "v-icon",
                                          { staticClass: "footer__icon" },
                                          [t._v(t._s(n.icon))],
                                        ),
                                        t._v(
                                          "\n            " +
                                            t._s(n.title) +
                                            "\n          ",
                                        ),
                                      ],
                                      1,
                                    )
                                  : e(
                                      "a",
                                      {
                                        staticClass: "footer__link",
                                        attrs: { href: n.to },
                                        on: {
                                          click: function (e) {
                                            return (
                                              e.preventDefault(),
                                              t.$EVT.$emit(t.$EVT.GOTO, n.to)
                                            );
                                          },
                                        },
                                      },
                                      [
                                        e(
                                          "v-icon",
                                          { staticClass: "footer__icon" },
                                          [t._v(t._s(n.icon))],
                                        ),
                                        t._v(
                                          "\n            " +
                                            t._s(n.title) +
                                            "\n          ",
                                        ),
                                      ],
                                      1,
                                    ),
                            ],
                          );
                        }),
                        0,
                      ),
                    ]),
                  ],
                  1,
                ),
              ]);
            },
          ],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    302: function (t, n, r) {
      "use strict";
      r.r(n);
      var o = r(17),
        c = (r(45), r(20), r(28), r(35), r(36), r(29), r(14), r(30), r(78)),
        l = r.n(c);
      function d(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function _(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? d(Object(n), !0).forEach(function (e) {
                Object(o.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : d(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      var h = {
          components: { NavDrawerItem: r(307).default },
          beforeCreate: function () {},
          data: function () {
            return {};
          },
          mounted: function () {
            var t = this;
            if (
              (this.$EVT.$on(this.$EVT.ROUTE_CHANGED, function () {
                t.onRouteChanged();
              }),
              this.$EVT.$on(this.$EVT.OPEN, function (e) {
                e === t.$EVT.NAV_DRAWER
                  ? (t.$App.nav.drawer.is_visible = !0)
                  : (t.$App.nav.drawer.is_visible = !1);
              }),
              !this.$App.is_safari)
            )
              try {
                new SimpleBar(this.$refs.nav_drawer_simple_bar);
              } catch (t) {
                e(t);
              }
          },
          methods: {
            onRouteChanged: function () {
              this.$App.nav.drawer.is_visible = !1;
            },
          },
          computed: {
            account_items: function () {
              var t = this,
                e = this.$S.user
                  ? this.$App.nav.account_items
                  : this.$App.nav.guest_items;
              return (e = l.a.filter(e, function (e) {
                return "Admin" == e.title
                  ? t.$S.user && t.$S.user.is_admin
                  : !e.is_external_link;
              }));
            },
            channel_items: function () {
              if (!this.$S.playlists || 0 == this.$S.playlists.length)
                return [];
              var t = [],
                e = null,
                n = null,
                r = null;
              return (
                l.a.each(this.$S.playlists, function (o) {
                  o.is_mutable
                    ? t.push(
                        _(
                          _({}, o),
                          {},
                          {
                            icon: "mdi-playlist-play",
                            to: "/playlists/".concat(o.slug),
                          },
                        ),
                      )
                    : "Liked Videos" == o.title
                      ? (r = _(
                          _({}, o),
                          {},
                          {
                            icon: "mdi-heart",
                            to: "/playlists/".concat(o.slug),
                          },
                        ))
                      : "History" == o.title
                        ? (e = _(
                            _({}, o),
                            {},
                            {
                              icon: "mdi-history",
                              to: "/playlists/".concat(o.slug),
                            },
                          ))
                        : "Watch Later" == o.title &&
                          (n = _(
                            _({}, o),
                            {},
                            {
                              icon: "mdi-clock",
                              to: "/playlists/".concat(o.slug),
                            },
                          ));
                }),
                [_({}, e), _({}, n), _({}, r)].concat(t)
              );
            },
          },
        },
        f = r(15),
        component = Object(f.a)(
          h,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "v-navigation-drawer",
              {
                attrs: {
                  app: "",
                  "disable-resize-watcher": "",
                  "disable-route-watcher": "",
                  fixed: "",
                  floating: "",
                  temporary: "",
                  width: "250",
                },
                model: {
                  value: t.$App.nav.drawer.is_visible,
                  callback: function (e) {
                    t.$set(t.$App.nav.drawer, "is_visible", e);
                  },
                  expression: "$App.nav.drawer.is_visible",
                },
              },
              [
                e("div", { staticClass: "ndsc_fader" }),
                t._v(" "),
                e(
                  "div",
                  {
                    ref: "nav_drawer_simple_bar",
                    staticClass: "navigation-drawer-scroll-container",
                  },
                  [
                    e("div", { staticClass: "divider mt-5 pt-3" }),
                    t._v(" "),
                    e(
                      "v-list",
                      { staticClass: "nav-drawer-list" },
                      t._l(t.$App.nav.main_items, function (n, i) {
                        return e(
                          "NavDrawerItem",
                          { key: i, attrs: { item: n } },
                          [
                            e(
                              "v-list-tile-action",
                              [e("v-icon", [t._v(t._s(n.icon))])],
                              1,
                            ),
                            t._v(" "),
                            e(
                              "v-list-tile-content",
                              [e("v-list-tile-title", [t._v(t._s(n.title))])],
                              1,
                            ),
                          ],
                          1,
                        );
                      }),
                      1,
                    ),
                    t._v(" "),
                    t.$S.user
                      ? e(
                          "v-list",
                          { staticClass: "nav-drawer-list" },
                          [
                            e(
                              "v-list-tile",
                              {
                                attrs: {
                                  ripple: "",
                                  exact: "",
                                  to: "/channel",
                                },
                              },
                              [
                                e(
                                  "v-list-tile-content",
                                  [e("v-list-tile-title", [t._v("CHANNEL")])],
                                  1,
                                ),
                              ],
                              1,
                            ),
                            t._v(" "),
                            t._l(t.channel_items, function (n, i) {
                              return e(
                                "NavDrawerItem",
                                { key: i, attrs: { item: n } },
                                [
                                  e(
                                    "v-list-tile-action",
                                    [e("v-icon", [t._v(t._s(n.icon))])],
                                    1,
                                  ),
                                  t._v(" "),
                                  e(
                                    "v-list-tile-content",
                                    [
                                      e("v-list-tile-title", [
                                        t._v(t._s(n.title)),
                                      ]),
                                    ],
                                    1,
                                  ),
                                ],
                                1,
                              );
                            }),
                          ],
                          2,
                        )
                      : t._e(),
                    t._v(" "),
                    e(
                      "v-list",
                      { staticClass: "nav-drawer-list" },
                      [
                        e(
                          "v-subheader",
                          {
                            staticClass: "subheading grey--text text--darken-1",
                          },
                          [t._v("ACCOUNT")],
                        ),
                        t._v(" "),
                        t._l(t.account_items, function (n, i) {
                          return e(
                            "NavDrawerItem",
                            { key: i, attrs: { item: n } },
                            [
                              e(
                                "v-list-tile-action",
                                [e("v-icon", [t._v(t._s(n.icon))])],
                                1,
                              ),
                              t._v(" "),
                              e(
                                "v-list-tile-content",
                                [e("v-list-tile-title", [t._v(t._s(n.title))])],
                                1,
                              ),
                            ],
                            1,
                          );
                        }),
                      ],
                      2,
                    ),
                    t._v(" "),
                    e(
                      "v-list",
                      { staticClass: "nav-drawer-list" },
                      [
                        e(
                          "v-subheader",
                          {
                            staticClass: "subheading grey--text text--darken-1",
                          },
                          [t._v("CONNECT")],
                        ),
                        t._v(" "),
                        t._l(t.$App.nav.connect_items, function (n, i) {
                          return e(
                            "NavDrawerItem",
                            { key: i, attrs: { item: n } },
                            [
                              e(
                                "v-list-tile-action",
                                [e("v-icon", [t._v(t._s(n.icon))])],
                                1,
                              ),
                              t._v(" "),
                              e(
                                "v-list-tile-content",
                                [e("v-list-tile-title", [t._v(t._s(n.title))])],
                                1,
                              ),
                            ],
                            1,
                          );
                        }),
                      ],
                      2,
                    ),
                    t._v(" "),
                    e(
                      "v-list",
                      { staticClass: "nav-drawer-list" },
                      [
                        e(
                          "v-subheader",
                          {
                            staticClass: "subheading grey--text text--darken-1",
                          },
                          [t._v("RESOURCES")],
                        ),
                        t._v(" "),
                        t._l(t.$App.nav.resources_items, function (n, i) {
                          return e(
                            "NavDrawerItem",
                            { key: i, attrs: { item: n } },
                            [
                              e(
                                "v-list-tile-action",
                                [e("v-icon", [t._v(t._s(n.icon))])],
                                1,
                              ),
                              t._v(" "),
                              e(
                                "v-list-tile-content",
                                [e("v-list-tile-title", [t._v(t._s(n.title))])],
                                1,
                              ),
                            ],
                            1,
                          );
                        }),
                      ],
                      2,
                    ),
                    t._v(" "),
                    e("div", { staticClass: "divider mb-5" }),
                  ],
                  1,
                ),
                t._v(" "),
                e("div", { staticClass: "ndsc_fader_bottom" }),
              ],
            );
          },
          [],
          !1,
          null,
          null,
          null,
        );
      n.default = component.exports;
    },
    303: function (t, e, n) {
      "use strict";
      n.r(e);
      (n(54), n(22), n(19), n(81), n(20), n(46), n(14), n(52));
      var r = n(78),
        o = n.n(r),
        c = {
          components: {},
          beforeCreate: function () {},
          data: function () {
            return {
              no_toolbar_pages: ["downloads-id", "edit-my-channel"],
              is_quick_search_results_visible: !1,
              quick_search_results_y: 0,
              quick_search_results_x: 0,
              quick_search_results_width: 200,
            };
          },
          mounted: function () {
            this.$EVT.$on(this.$EVT.FOCUS_SEARCH_INPUT, this.focusSearchInput);
          },
          beforeDestroy: function () {
            this.$EVT.$off(this.$EVT.FOCUS_SEARCH_INPUT, this.focusSearchInput);
          },
          methods: {
            emitDoNewSearch: function () {
              (this.activateQuickSearchResults("#search_bar"),
                this.$EVT.$emit(this.$EVT.DO_NEW_SEARCH));
            },
            emitDoSearch: function () {
              this.$EVT.$emit(this.$EVT.DO_SEARCH);
            },
            focusSearchInput: function () {
              this.$refs.search_input.focus();
            },
            onSearchBarFocused: function () {
              ((this.$App.nav.toolbar.is_search_bar_focused = !0),
                this.$EVT.$emit(this.$EVT.GOTO, "/search"));
            },
            activateQuickSearchResults: function (t) {
              var e = 0,
                n = 0,
                r = document.querySelector(t),
                rect = r.getBoundingClientRect();
              if (void 0 === rect.y) {
                var o = this.$S.getOffset(r);
                ((n = o.top), (e = o.left - 0.5 * rect.width));
              } else ((n = rect.y), (e = rect.x));
              ((this.quick_search_results_width = rect.width),
                (this.quick_search_results_x = e),
                (this.quick_search_results_y = n),
                (this.is_quick_search_results_visible = !0));
            },
          },
          computed: {
            is_toolbar_enabled: function () {
              return !this.no_toolbar_pages.includes(this.$route.name);
            },
            toolbar_classes: function () {
              return this.$S.scrollY > 0 ||
                (("hentai-videos-id" == this.$nuxt.$route.name ||
                  "videos-hentai-id" == this.$nuxt.$route.name) &&
                  this.$S.browser_width < 600) ||
                "channel" == this.$nuxt.$route.name ||
                "channels-id" == this.$nuxt.$route.name ||
                "account" == this.$nuxt.$route.name
                ? "semi-transparent"
                : "transparent elevation-0";
            },
            avatar_background_style: function () {
              var t = this.$S.user,
                e = "";
              if (t) {
                if (!t.avatar_url) {
                  var i = t.id % 59;
                  return "https://hanime-cdn.com/images/default-avatars/".concat(
                    i,
                    ".png",
                  );
                }
                e = this.$App.jetpackUrl(
                  t.avatar_url,
                  100,
                  null,
                  null,
                  "apbcb",
                );
              }
              return "transparent url(".concat(e, ") center center / cover");
            },
            account_items: function () {
              var t = this;
              return o.a.filter(this.$App.nav.account_items, function (e) {
                return "Admin" != e.title || (t.$S.user && t.$S.user.is_admin);
              });
            },
          },
        },
        l = n(15),
        component = Object(l.a)(
          c,
          function () {
            var t = this,
              e = t._self._c;
            return t.is_toolbar_enabled
              ? e(
                  "v-toolbar",
                  { class: t.toolbar_classes, attrs: { fixed: "" } },
                  [
                    e(
                      "v-btn",
                      {
                        staticClass: "nav_drawer_toggle",
                        attrs: { large: "", icon: "" },
                        on: {
                          click: function (e) {
                            (e.stopPropagation(),
                              (t.$App.nav.drawer.is_visible =
                                !t.$App.nav.drawer.is_visible));
                          },
                        },
                      },
                      [e("v-icon", [t._v("mdi-menu")])],
                      1,
                    ),
                    t._v(" "),
                    e(
                      "v-btn",
                      {
                        staticClass: "logo-text",
                        attrs: { to: "/", flat: "", large: "" },
                      },
                      [
                        t._v("\n    hanime"),
                        e("span", { staticClass: "highlight" }, [t._v(".")]),
                        t._v("tv\n  "),
                      ],
                    ),
                    t._v(" "),
                    e("div", {
                      staticClass: "divider-vertical hidden-sm-and-down",
                    }),
                    t._v(" "),
                    e(
                      "span",
                      { staticClass: "page-location-text hidden-sm-and-down" },
                      [t._v(t._s(t.$S.page_name))],
                    ),
                    t._v(" "),
                    e("v-spacer", { staticClass: "spacer-left" }),
                    t._v(" "),
                    e(
                      "v-card",
                      {
                        class: [
                          "toolbar-search flex",
                          { focused: t.$App.nav.toolbar.is_search_bar_focused },
                        ],
                        attrs: { light: "", flat: "", hover: !0 },
                      },
                      [
                        e("v-text-field", {
                          ref: "search_input",
                          staticClass:
                            "toolbar-search__bar flex row justify-center align-center relative",
                          attrs: {
                            id: "search_bar",
                            label: "Search",
                            "single-line": "",
                            light: "",
                            "hide-details": "",
                            autocomplete: "off",
                            autocorrect: "off",
                            autocapitalize: "off",
                            spellcheck: "false",
                          },
                          on: {
                            focus: t.onSearchBarFocused,
                            blur: function (e) {
                              t.$App.nav.toolbar.is_search_bar_focused = !1;
                            },
                            input: t.emitDoNewSearch,
                            keyup: function (e) {
                              return !e.type.indexOf("key") &&
                                t._k(e.keyCode, "enter", 13, e.key, "Enter")
                                ? null
                                : t.emitDoSearch.apply(null, arguments);
                            },
                          },
                          model: {
                            value: t.$S.search.search_text,
                            callback: function (e) {
                              t.$set(t.$S.search, "search_text", e);
                            },
                            expression: "$S.search.search_text",
                          },
                        }),
                        t._v(" "),
                        "search" != t.$nuxt.$route.name
                          ? e(
                              "v-menu",
                              {
                                staticClass: "flex",
                                attrs: {
                                  "content-class": "fixed toolbar-search__menu",
                                  fixed: "",
                                  "close-delay": "0",
                                  "close-on-click": !0,
                                  "position-x": t.quick_search_results_x,
                                  "position-y": t.quick_search_results_y,
                                  "min-width": t.quick_search_results_width,
                                  "max-width": t.quick_search_results_width,
                                },
                                model: {
                                  value: t.is_quick_search_results_visible,
                                  callback: function (e) {
                                    t.is_quick_search_results_visible = e;
                                  },
                                  expression: "is_quick_search_results_visible",
                                },
                              },
                              [
                                t.$S.search.search_response_payload &&
                                t.$S.search.search_response_payload.hits &&
                                t.$S.search.search_response_payload.hits
                                  .length > 0
                                  ? e(
                                      "v-list",
                                      {
                                        staticClass: "toolbar-search__results",
                                      },
                                      [
                                        t._l(
                                          t.$S.search.search_response_payload.hits.slice(
                                            0,
                                            4,
                                          ),
                                          function (n) {
                                            return e(
                                              "v-list-tile",
                                              {
                                                key: n.id,
                                                attrs: {
                                                  to: "/videos/hentai/".concat(
                                                    n.slug,
                                                  ),
                                                },
                                              },
                                              [
                                                e("v-list-tile-title", [
                                                  t._v(t._s(n.name)),
                                                ]),
                                              ],
                                              1,
                                            );
                                          },
                                        ),
                                        t._v(" "),
                                        t.$S.search.search_response_payload.hits
                                          .length > 4
                                          ? e(
                                              "v-list-tile",
                                              [
                                                e(
                                                  "v-btn",
                                                  {
                                                    staticClass: "flex row",
                                                    attrs: {
                                                      color: "primary",
                                                      to: "/search",
                                                    },
                                                  },
                                                  [
                                                    t._v(
                                                      "\n            Show More\n          ",
                                                    ),
                                                  ],
                                                ),
                                              ],
                                              1,
                                            )
                                          : t._e(),
                                      ],
                                      2,
                                    )
                                  : t.$S.search.search_text.length > 0 &&
                                      t.$S.search.search_response_payload &&
                                      t.$S.search.search_response_payload
                                        .hits &&
                                      0 ==
                                        t.$S.search.search_response_payload.hits
                                          .length
                                    ? e(
                                        "div",
                                        {
                                          staticClass:
                                            "toolbar-search__results flex row justify-center align-center py-3",
                                        },
                                        [t._v("No search results.")],
                                      )
                                    : e(
                                        "div",
                                        {
                                          staticClass:
                                            "toolbar-search__results flex row justify-center align-center py-3",
                                        },
                                        [
                                          e(
                                            "v-icon",
                                            {
                                              staticClass:
                                                "toolbar-search__arrow",
                                            },
                                            [
                                              t._v(
                                                "mdi-subdirectory-arrow-left",
                                              ),
                                            ],
                                          ),
                                          t._v(
                                            "\n        Search title or tags.\n      ",
                                          ),
                                        ],
                                        1,
                                      ),
                              ],
                              1,
                            )
                          : t._e(),
                        t._v(" "),
                        t.$S.is_searching
                          ? e("v-progress-circular", {
                              staticClass: "loading_spinner",
                              attrs: {
                                size: "18",
                                width: 2,
                                indeterminate: "",
                                color: "primary",
                              },
                            })
                          : t._e(),
                      ],
                      1,
                    ),
                    t._v(" "),
                    e("v-spacer", { staticClass: "spacer-right" }),
                    t._v(" "),
                    t.$S.user
                      ? e(
                          "div",
                          { staticClass: "user-options" },
                          [
                            e(
                              "v-menu",
                              {
                                attrs: {
                                  "offset-y": "",
                                  left: "",
                                  "nudge-right": 16,
                                  "content-class": "fixed user_options",
                                },
                              },
                              [
                                e(
                                  "v-btn",
                                  {
                                    staticClass: "avatar-container",
                                    attrs: {
                                      slot: "activator",
                                      icon: "",
                                      large: "",
                                    },
                                    slot: "activator",
                                  },
                                  [
                                    e("div", {
                                      staticClass: "avatar",
                                      style: {
                                        background: t.avatar_background_style,
                                      },
                                    }),
                                  ],
                                ),
                                t._v(" "),
                                e(
                                  "router-link",
                                  {
                                    staticClass: "user_options__top_wrapper",
                                    attrs: { to: "/account" },
                                  },
                                  [
                                    t.$S.user.is_able_to_access_premium
                                      ? e(
                                          "v-icon",
                                          { staticClass: "bg__upgraded" },
                                          [t._v("mdi-crown")],
                                        )
                                      : t._e(),
                                    t._v(" "),
                                    e(
                                      "div",
                                      {
                                        staticClass:
                                          "user_options__top flex row align-center",
                                      },
                                      [
                                        e(
                                          "v-btn",
                                          {
                                            class: [
                                              "avatar-container",
                                              {
                                                upgraded:
                                                  t.$S.user
                                                    .is_able_to_access_premium,
                                              },
                                            ],
                                            attrs: {
                                              slot: "activator",
                                              icon: "",
                                              large: "",
                                            },
                                            slot: "activator",
                                          },
                                          [
                                            e("div", {
                                              staticClass: "avatar",
                                              style: {
                                                background:
                                                  t.avatar_background_style,
                                              },
                                            }),
                                          ],
                                        ),
                                        t._v(" "),
                                        e(
                                          "div",
                                          {
                                            staticClass:
                                              "user_options__top__nameemail flex column justify-center align-left",
                                          },
                                          [
                                            e(
                                              "div",
                                              {
                                                staticClass:
                                                  "nameemail__name cut_text",
                                              },
                                              [
                                                t._v(t._s(t.$S.user.name)),
                                                e("span", [
                                                  t._v(
                                                    "#" +
                                                      t._s(t.$S.user.number),
                                                  ),
                                                ]),
                                              ],
                                            ),
                                            t._v(" "),
                                            e(
                                              "div",
                                              {
                                                staticClass:
                                                  "nameemail__email cut_text",
                                              },
                                              [t._v(t._s(t.$S.user.email))],
                                            ),
                                          ],
                                        ),
                                      ],
                                      1,
                                    ),
                                  ],
                                  1,
                                ),
                                t._v(" "),
                                e(
                                  "v-list",
                                  { staticClass: "user_options__list" },
                                  t._l(t.account_items, function (n, i) {
                                    return e(
                                      "div",
                                      { key: i },
                                      [
                                        n.is_external_link
                                          ? e(
                                              "v-list-tile",
                                              {
                                                attrs: { ripple: "" },
                                                on: {
                                                  click: function (e) {
                                                    return t.$EVT.$emit(
                                                      t.$EVT.NEW_TAB,
                                                      n.to,
                                                    );
                                                  },
                                                },
                                              },
                                              [
                                                e(
                                                  "v-list-tile-action",
                                                  [
                                                    e("v-icon", [
                                                      t._v(t._s(n.icon)),
                                                    ]),
                                                  ],
                                                  1,
                                                ),
                                                t._v(" "),
                                                e(
                                                  "v-list-tile-content",
                                                  [
                                                    e("v-list-tile-title", {
                                                      domProps: {
                                                        textContent: t._s(
                                                          n.title,
                                                        ),
                                                      },
                                                    }),
                                                  ],
                                                  1,
                                                ),
                                              ],
                                              1,
                                            )
                                          : e(
                                              "v-list-tile",
                                              {
                                                attrs: { ripple: "" },
                                                on: {
                                                  click: function (e) {
                                                    return t.$EVT.$emit(
                                                      t.$EVT.GOTO,
                                                      n.to,
                                                    );
                                                  },
                                                },
                                              },
                                              [
                                                e(
                                                  "v-list-tile-action",
                                                  [
                                                    e("v-icon", [
                                                      t._v(t._s(n.icon)),
                                                    ]),
                                                  ],
                                                  1,
                                                ),
                                                t._v(" "),
                                                e(
                                                  "v-list-tile-content",
                                                  [
                                                    e("v-list-tile-title", {
                                                      domProps: {
                                                        textContent: t._s(
                                                          n.title,
                                                        ),
                                                      },
                                                    }),
                                                  ],
                                                  1,
                                                ),
                                              ],
                                              1,
                                            ),
                                      ],
                                      1,
                                    );
                                  }),
                                  0,
                                ),
                              ],
                              1,
                            ),
                          ],
                          1,
                        )
                      : e(
                          "div",
                          { staticClass: "user-options" },
                          [
                            e(
                              "v-btn",
                              {
                                staticClass: "log-in-text",
                                attrs: {
                                  flat: "",
                                  large: t.$S.browser_width >= 600,
                                },
                                nativeOn: {
                                  click: function (e) {
                                    return (
                                      e.preventDefault(),
                                      e.stopPropagation(),
                                      t.$EVT.$emit(t.$EVT.GOTO, "/sign-in")
                                    );
                                  },
                                  mouseup: function (e) {
                                    return "button" in e && 1 !== e.button
                                      ? null
                                      : (e.preventDefault(),
                                        e.stopPropagation(),
                                        t.$EVT.$emit(
                                          t.$EVT.NEW_TAB,
                                          "/sign-in",
                                        ));
                                  },
                                },
                              },
                              [e("span", [t._v("Sign In")])],
                            ),
                            t._v(" "),
                            e(
                              "v-btn",
                              {
                                staticClass: "log-in-text hidden-sm-and-down",
                                attrs: { flat: "", large: "" },
                                nativeOn: {
                                  click: function (e) {
                                    return (
                                      e.preventDefault(),
                                      e.stopPropagation(),
                                      t.$EVT.$emit(
                                        t.$EVT.GOTO,
                                        "/create-account",
                                      )
                                    );
                                  },
                                  mouseup: function (e) {
                                    return "button" in e && 1 !== e.button
                                      ? null
                                      : (e.preventDefault(),
                                        e.stopPropagation(),
                                        t.$EVT.$emit(
                                          t.$EVT.NEW_TAB,
                                          "/create-account",
                                        ));
                                  },
                                },
                              },
                              [
                                e(
                                  "span",
                                  { staticClass: "hidden-lg-and-up" },
                                  [e("v-icon", [t._v("mdi-account-plus")])],
                                  1,
                                ),
                                t._v(" "),
                                e(
                                  "span",
                                  { staticClass: "hidden-md-and-down" },
                                  [t._v("Create Account")],
                                ),
                              ],
                            ),
                          ],
                          1,
                        ),
                  ],
                  1,
                )
              : t._e();
          },
          [],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    304: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = {
          beforeCreate: function () {},
          data: function () {
            return {};
          },
          mounted: function () {
            (this.$EVT.$on(this.$EVT.SNACK, this.snackNormal),
              this.$EVT.$on(this.$EVT.SNACK_ERR, this.snackErr));
          },
          beforeDestroy: function () {
            (this.$EVT.$off(this.$EVT.SNACK, this.snackNormal),
              this.$EVT.$off(this.$EVT.SNACK_ERR, this.snackErr));
          },
          methods: {
            snackNormal: function (text) {
              ((this.$S.snackbar.text = text),
                (this.$S.snackbar.context = "secondary"),
                (this.$S.snackbar.mode = "single-line"),
                (this.$S.snackbar.is_visible = !0));
            },
            snackErr: function (text) {
              ((this.$S.snackbar.text = text),
                (this.$S.snackbar.context = "error"),
                (this.$S.snackbar.mode = "multi-line"),
                (this.$S.snackbar.is_visible = !0));
            },
          },
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "v-snackbar",
              {
                attrs: {
                  timeout: t.$S.snackbar.timeout,
                  color: t.$S.snackbar.context,
                  "multi-line": "multi-line" === t.$S.snackbar.mode,
                  vertical: "vertical" === t.$S.snackbar.mode,
                  top: "top" === t.$S.snackbar.y,
                  bottom: "bottom" === t.$S.snackbar.y,
                  right: "right" === t.$S.snackbar.x,
                  left: "left" === t.$S.snackbar.x,
                },
                model: {
                  value: t.$S.snackbar.is_visible,
                  callback: function (e) {
                    t.$set(t.$S.snackbar, "is_visible", e);
                  },
                  expression: "$S.snackbar.is_visible",
                },
              },
              [
                e("div", [t._v(t._s(t.$S.snackbar.text))]),
                t._v(" "),
                e(
                  "v-btn",
                  {
                    attrs: {
                      flat: "",
                      color:
                        "error" == t.$S.snackbar.context ? "#FFF" : "primary",
                    },
                    on: {
                      click: function (e) {
                        t.$S.snackbar.is_visible = !1;
                      },
                    },
                  },
                  [t._v("Close")],
                ),
              ],
              1,
            );
          },
          [],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    305: function (t, n, r) {
      "use strict";
      r.r(n);
      var o = r(5),
        c =
          (r(27),
          r(22),
          r(62),
          {
            beforeCreate: function () {},
            data: function () {
              return {};
            },
            mounted: function () {
              var t = this;
              this.$EVT.$on(this.$EVT.OPEN, function (e, n) {
                e === t.$EVT.CONTACT_US_DIALOG
                  ? ((t.$S.contact_us_dialog.is_sent = !1),
                    (t.$S.contact_us_dialog.is_video_report = !!n),
                    t.$S.contact_us_dialog.is_video_report
                      ? (t.$S.contact_us_dialog.subject = "".concat(
                          t.$S.data.video.hentai_video.name,
                          " error",
                        ))
                      : (t.$S.contact_us_dialog.subject = ""),
                    t.$S.user &&
                      (t.$S.contact_us_dialog.email = t.$S.user.email),
                    (t.$S.contact_us_dialog.is_visible = !0))
                  : (t.$S.contact_us_dialog.is_visible = !1);
              });
            },
            methods: {
              validateEmail: function () {
                var t = this.$S.contact_us_dialog.email;
                return (
                  l("email:", t),
                  0 == t.length || !!this.$S.isValidEmail(t) || "Invalid email"
                );
              },
              send: function () {
                var t = this;
                return Object(o.a)(
                  regeneratorRuntime.mark(function n() {
                    var r, o, c, l;
                    return regeneratorRuntime.wrap(
                      function (n) {
                        for (;;)
                          switch ((n.prev = n.next)) {
                            case 0:
                              if (!t.$S.is_loading) {
                                n.next = 1;
                                break;
                              }
                              return n.abrupt("return");
                            case 1:
                              return (
                                (t.$S.is_loading = !0),
                                (r = {
                                  width: t.$S.browser_width,
                                  height: t.$S.browser_height,
                                  referrer: t.$S.referrer,
                                  source: "web",
                                  version: t.$S.version,
                                  url: window.location.href,
                                  subject: t.$S.contact_us_dialog.subject,
                                  email: t.$S.contact_us_dialog.email,
                                  message: t.$S.contact_us_dialog.message,
                                }),
                                t.$S.contact_us_dialog.is_video_report &&
                                  ((r.is_video_report = "1"),
                                  void 0 !==
                                    (o = document.querySelector("video")) &&
                                    o &&
                                    (r.video_url = o.currentSrc),
                                  (r.hentai_video_id =
                                    t.$S.data.video.hentai_video.id),
                                  (r.hentai_video_slug =
                                    t.$S.data.video.hentai_video.slug)),
                                (n.prev = 2),
                                (n.next = 3),
                                t.$post(
                                  "".concat(
                                    t.$getApiBaseUrl(),
                                    "/rapi/v7/feedbacks",
                                  ),
                                  r,
                                )
                              );
                            case 3:
                              ((c = n.sent), c.data, (n.next = 5));
                              break;
                            case 4:
                              ((n.prev = 4), (l = n.catch(2)), e(l));
                            case 5:
                              ((t.$S.contact_us_dialog.is_sent = !0),
                                setTimeout(function () {
                                  ((t.$S.contact_us_dialog.is_visible = !1),
                                    t.$nextTick(function () {
                                      ((t.$S.is_loading = !1),
                                        (t.$S.contact_us_dialog.subject = ""),
                                        (t.$S.contact_us_dialog.email = ""),
                                        (t.$S.contact_us_dialog.message = ""));
                                    }));
                                }, 2e3));
                            case 6:
                            case "end":
                              return n.stop();
                          }
                      },
                      n,
                      null,
                      [[2, 4]],
                    );
                  }),
                )();
              },
            },
            computed: {
              is_send_enabled: function () {
                return (
                  !this.$S.is_loading &&
                  (!!this.$S.contact_us_dialog.is_video_report ||
                    this.$S.contact_us_dialog.message.length > 0)
                );
              },
            },
          }),
        d = r(15),
        component = Object(d.a)(
          c,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "v-dialog",
              {
                attrs: {
                  lazy: "",
                  transition: "slide-y-transition",
                  "content-class": "contact_us_dialog",
                },
                model: {
                  value: t.$S.contact_us_dialog.is_visible,
                  callback: function (e) {
                    t.$set(t.$S.contact_us_dialog, "is_visible", e);
                  },
                  expression: "$S.contact_us_dialog.is_visible",
                },
              },
              [
                e("div", { staticClass: "borders" }),
                t._v(" "),
                e(
                  "v-card",
                  [
                    e(
                      "v-card-title",
                      { staticClass: "dialog__top primary" },
                      [
                        e(
                          "v-btn",
                          {
                            staticClass: "close_btn",
                            attrs: { icon: "", large: "" },
                            on: {
                              click: function (e) {
                                t.$S.contact_us_dialog.is_visible = !1;
                              },
                            },
                          },
                          [e("v-icon", [t._v("mdi-window-close")])],
                          1,
                        ),
                        t._v(" "),
                        t.$S.contact_us_dialog.is_video_report
                          ? e("div", { staticClass: "tab_title" }, [
                              t._v("Report Video"),
                            ])
                          : e("div", { staticClass: "tab_title" }, [
                              t._v("Contact Us"),
                            ]),
                      ],
                      1,
                    ),
                    t._v(" "),
                    t.$S.contact_us_dialog.is_sent
                      ? e("v-card-text", [
                          t._v(
                            "\n      Thanks, your feedback has been sent!\n    ",
                          ),
                        ])
                      : e(
                          "v-card-text",
                          [
                            t.$S.contact_us_dialog.is_video_report
                              ? e(
                                  "div",
                                  { staticClass: "protip" },
                                  [
                                    e("v-icon", [
                                      t._v("mdi-information-variant"),
                                    ]),
                                    t._v(" "),
                                    e("strong", [t._v("Did you know:")]),
                                    t._v(
                                      " Refreshing the page will fix broken videos most of the time.\n      ",
                                    ),
                                  ],
                                  1,
                                )
                              : t._e(),
                            t._v(" "),
                            e("v-text-field", {
                              attrs: { label: "Subject (optional)" },
                              model: {
                                value: t.$S.contact_us_dialog.subject,
                                callback: function (e) {
                                  t.$set(t.$S.contact_us_dialog, "subject", e);
                                },
                                expression: "$S.contact_us_dialog.subject",
                              },
                            }),
                            t._v(" "),
                            e("v-text-field", {
                              attrs: {
                                label: "Reply Email (optional)",
                                rules: [t.validateEmail],
                              },
                              model: {
                                value: t.$S.contact_us_dialog.email,
                                callback: function (e) {
                                  t.$set(t.$S.contact_us_dialog, "email", e);
                                },
                                expression: "$S.contact_us_dialog.email",
                              },
                            }),
                            t._v(" "),
                            e("v-text-field", {
                              attrs: {
                                name: "contact_us_text",
                                label: "Comments ".concat(
                                  t.$S.contact_us_dialog.is_video_report
                                    ? "(optional)"
                                    : "",
                                ),
                                outline: "",
                                type: "text",
                                "multi-line": "",
                                "hide-details": "",
                              },
                              model: {
                                value: t.$S.contact_us_dialog.message,
                                callback: function (e) {
                                  t.$set(t.$S.contact_us_dialog, "message", e);
                                },
                                expression: "$S.contact_us_dialog.message",
                              },
                            }),
                          ],
                          1,
                        ),
                    t._v(" "),
                    e(
                      "v-card-actions",
                      [
                        e("v-spacer"),
                        t._v(" "),
                        e(
                          "v-btn",
                          {
                            attrs: {
                              flat: "",
                              color: "primary",
                              disabled: !t.is_send_enabled,
                            },
                            on: {
                              click: function (e) {
                                return t.send();
                              },
                            },
                          },
                          [t._v("Send")],
                        ),
                      ],
                      1,
                    ),
                  ],
                  1,
                ),
              ],
              1,
            );
          },
          [],
          !1,
          null,
          null,
          null,
        );
      n.default = component.exports;
    },
    306: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = {
          beforeCreate: function () {},
          beforeMount: function () {
            var t = this;
            this.$EVT.$on(
              this.$EVT.ACTIVATE_GENERAL_CONFIRMATION_DIALOG,
              function (e) {
                var n = t.$S.general_confirmation_dialog;
                ((n.title = e.title),
                  (n.body = e.body),
                  (n.confirm_button_text = e.confirm_button_text),
                  (n.confirmation_callback = e.confirmation_callback),
                  (n.is_persistent = e.is_persistent),
                  (n.is_mini_close_button_visible =
                    e.is_mini_close_button_visible),
                  (n.is_cancel_button_visible = e.is_cancel_button_visible),
                  (n.cancel_button_text = e.cancel_button_text || "Cancel"),
                  t.$nextTick(function () {
                    n.is_visible = !0;
                  }));
              },
            );
          },
          data: function () {
            return {};
          },
          mounted: function () {},
          methods: {
            confirm: function () {
              (this.$S.general_confirmation_dialog.confirmation_callback &&
                "function" ==
                  typeof this.$S.general_confirmation_dialog
                    .confirmation_callback &&
                this.$S.general_confirmation_dialog.confirmation_callback(),
                (this.$S.general_confirmation_dialog.is_visible = !1));
            },
          },
          computed: {},
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "v-dialog",
              {
                attrs: {
                  lazy: "",
                  scrollable: "",
                  persistent: t.$S.general_confirmation_dialog.is_persistent,
                  transition: "slide-y-transition",
                  "content-class": "general_confirmation_dialog",
                },
                model: {
                  value: t.$S.general_confirmation_dialog.is_visible,
                  callback: function (e) {
                    t.$set(t.$S.general_confirmation_dialog, "is_visible", e);
                  },
                  expression: "$S.general_confirmation_dialog.is_visible",
                },
              },
              [
                e("div", { staticClass: "borders" }),
                t._v(" "),
                e(
                  "v-card",
                  [
                    e(
                      "v-card-title",
                      { staticClass: "dialog__top primary" },
                      [
                        t.$S.general_confirmation_dialog
                          .is_mini_close_button_visible
                          ? e(
                              "v-btn",
                              {
                                staticClass: "close_btn",
                                attrs: { icon: "", large: "" },
                                on: {
                                  click: function (e) {
                                    t.$S.general_confirmation_dialog.is_visible =
                                      !1;
                                  },
                                },
                              },
                              [e("v-icon", [t._v("mdi-window-close")])],
                              1,
                            )
                          : t._e(),
                        t._v(" "),
                        e("div", { staticClass: "tab_title" }, [
                          t._v(t._s(t.$S.general_confirmation_dialog.title)),
                        ]),
                      ],
                      1,
                    ),
                    t._v(" "),
                    e("v-card-text", {
                      staticClass: "body",
                      domProps: {
                        innerHTML: t._s(t.$S.general_confirmation_dialog.body),
                      },
                    }),
                    t._v(" "),
                    e(
                      "v-card-actions",
                      [
                        e("v-spacer"),
                        t._v(" "),
                        t.$S.general_confirmation_dialog
                          .is_cancel_button_visible
                          ? e(
                              "v-btn",
                              {
                                attrs: { flat: "" },
                                on: {
                                  click: function (e) {
                                    t.$S.general_confirmation_dialog.is_visible =
                                      !1;
                                  },
                                },
                              },
                              [
                                t._v(
                                  t._s(
                                    t.$S.general_confirmation_dialog
                                      .cancel_button_text || "Cancel",
                                  ),
                                ),
                              ],
                            )
                          : t._e(),
                        t._v(" "),
                        e(
                          "v-btn",
                          {
                            attrs: { color: "primary" },
                            on: {
                              click: function (e) {
                                return t.confirm();
                              },
                            },
                          },
                          [
                            t._v(
                              t._s(
                                t.$S.general_confirmation_dialog
                                  .confirm_button_text,
                              ),
                            ),
                          ],
                        ),
                      ],
                      1,
                    ),
                  ],
                  1,
                ),
              ],
              1,
            );
          },
          [],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    307: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = { props: ["item"], beforeCreate: function () {} },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this,
              e = t._self._c;
            return t.item.is_special_action
              ? e(
                  "v-list-tile",
                  {
                    attrs: { ripple: "" },
                    on: {
                      click: function (e) {
                        return (
                          e.preventDefault(),
                          e.stopPropagation(),
                          t.$EVT.$emit(t.$EVT.GOTO, t.item.to)
                        );
                      },
                      mouseup: function (e) {
                        return "button" in e && 1 !== e.button
                          ? null
                          : (e.preventDefault(),
                            e.stopPropagation(),
                            t.$EVT.$emit(t.$EVT.GOTO, t.item.to));
                      },
                    },
                  },
                  [t._t("default")],
                  2,
                )
              : t.item.is_external_link
                ? e(
                    "v-list-tile",
                    {
                      attrs: {
                        ripple: "",
                        href: t.item.to,
                        target: "_blank",
                        rel: "noopener",
                      },
                    },
                    [t._t("default")],
                    2,
                  )
                : e(
                    "v-list-tile",
                    { attrs: { ripple: "", to: t.item.to, exact: "" } },
                    [t._t("default")],
                    2,
                  );
          },
          [],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    42: function (t, n, r) {
      "use strict";
      var o = r(5);
      (r(27),
        r(20),
        r(46),
        r(53),
        r(54),
        r(280),
        r(22),
        r(29),
        r(14),
        r(19),
        r(52),
        r(81),
        r(207),
        r(97));
      function c() {
        return (c = Object(o.a)(
          regeneratorRuntime.mark(function t(e) {
            var n, data, r;
            return regeneratorRuntime.wrap(function (t) {
              for (;;)
                switch ((t.prev = t.next)) {
                  case 0:
                    return (
                      (t.next = 1),
                      window.ctx.$get(window.ctx.$config.SEARCH_HVS_URL)
                    );
                  case 1:
                    ((n = t.sent),
                      (data = n.data),
                      (window.search_hvs = data),
                      (r = new MiniSearch({
                        fields: ["name", "search_titles", "description"],
                        storeFields: [
                          "id",
                          "name",
                          "slug",
                          "views",
                          "cover_url",
                          "brand",
                          "brand_id",
                          "likes",
                          "tags",
                          "created_at",
                          "released_at",
                        ],
                        searchOptions: {
                          boost: {
                            name: 2,
                            search_titles: 1.5,
                            description: 1.1,
                          },
                          fuzzy: 0.1,
                          prefix: !0,
                        },
                      })).addAll(window.search_hvs),
                      (window.ms = r),
                      l(e.$S));
                  case 2:
                  case "end":
                    return t.stop();
                }
            }, t);
          }),
        )).apply(this, arguments);
      }
      function l(t) {
        return d.apply(this, arguments);
      }
      function d() {
        return (d = Object(o.a)(
          regeneratorRuntime.mark(function t(e) {
            var n, r, o, c, l, d;
            return regeneratorRuntime.wrap(function (t) {
              for (;;)
                switch ((t.prev = t.next)) {
                  case 0:
                    n = {
                      search_text: e.search.search_text,
                      page: e.search.page - 1,
                    };
                    try {
                      ((r =
                        0 == n.search_text.length
                          ? search_hvs
                          : ms.search(n.search_text, { combineWith: "AND" })),
                        (o = n.search_text.toLowerCase()),
                        (r = h(
                          (r = f(
                            (r = r
                              .map(function (t) {
                                return (
                                  t.name
                                    .toLowerCase()
                                    .split(/\s+/)
                                    .some(function (t) {
                                      return t.startsWith(o);
                                    }) && (t.score *= 1.5),
                                  t
                                );
                              })
                              .sort(function (a, b) {
                                return b.score - a.score;
                              })),
                            e,
                          )),
                          e,
                        )),
                        (c = e.search.page_size),
                        (l = Math.ceil(r.length / c)),
                        (d = n.page + 1),
                        (r = r.slice(n.page * c, (n.page + 1) * c)),
                        Vue.set(e.search, "search_response_payload", {
                          hits: r,
                          page: d,
                          nbPages: l,
                        }),
                        (e.search.page = d),
                        (e.search.number_of_pages = l),
                        (window.hits = r));
                    } catch (t) {
                      console.error(t);
                    }
                  case 1:
                  case "end":
                    return t.stop();
                }
            }, t);
          }),
        )).apply(this, arguments);
      }
      function h(t, e) {
        var n = e.search.order_by,
          r = e.search.ordering;
        return (n && r && (t = _.orderBy(t, [n], [r])), t);
      }
      function f(t, e) {
        if (
          ((e.search.is_using_preferences = "search" == $nuxt.$route.name),
          !e.search.is_using_preferences)
        )
          return t;
        var n = [],
          r = [],
          o = [];
        return (
          (n = e.search.tags
            .filter(function (t) {
              return t.is_active;
            })
            .map(function (t) {
              return t.text.toLowerCase();
            })),
          (r = e.search.brands
            .filter(function (t) {
              return t.is_active;
            })
            .map(function (t) {
              return t.id;
            })),
          (o = e.search.blacklisted_tags
            .filter(function (t) {
              return t.is_active;
            })
            .map(function (t) {
              return t.text.toLowerCase();
            })),
          n.length > 0 &&
            (t =
              "all" == e.search.tags_match
                ? t.filter(function (t) {
                    return n.every(function (e) {
                      return t.tags.includes(e);
                    });
                  })
                : t.filter(function (t) {
                    return n.some(function (e) {
                      return t.tags.includes(e);
                    });
                  })),
          r.length > 0 &&
            (t = t.filter(function (t) {
              return r.includes(t.brand_id);
            })),
          o.length > 0 &&
            (t = t.filter(function (t) {
              return !o.some(function (e) {
                return t.tags.includes(e);
              });
            })),
          t
        );
      }
      function v(t) {
        var e = {},
          n = t.search.order_by || "created_at_unix",
          r = t.search.ordering || "desc";
        return ((e["".concat(n)] = { order: r }), ["_score", e]);
      }
      function m() {
        return (m = Object(o.a)(
          regeneratorRuntime.mark(function t(n) {
            var r, o;
            return regeneratorRuntime.wrap(function (t) {
              for (;;)
                switch ((t.prev = t.next)) {
                  case 0:
                    localStorage.removeItem("user_search_option");
                    try {
                      ((r = {
                        order_by: n.search.order_by,
                        ordering: n.search.ordering,
                        tags_match: n.search.tags_match,
                        tags: n.search.tags,
                        brands: n.search.brands,
                        blacklisted_tags: n.search.blacklisted_tags,
                      }),
                        (o = JSON.stringify(r)),
                        localStorage.setItem("user_search_option_v2", o));
                    } catch (t) {
                      e("Error saving to localStorage:", t.message);
                    }
                  case 1:
                  case "end":
                    return t.stop();
                }
            }, t);
          }),
        )).apply(this, arguments);
      }
      n.a = {
        init: function (t) {
          return c.apply(this, arguments);
        },
        doSearch: l,
        buildSortingConfig: v,
        restoreUserSearchState: function (t) {
          var n = null;
          try {
            var r = localStorage.getItem("user_search_option_v2");
            if (null === r) return null;
            n = JSON.parse(r);
          } catch (t) {
            return (e("Error loading from localStorage:", t.message), null);
          }
          (n.order_by &&
            ((t.search.order_by = n.order_by),
            (t.search.ordering = n.ordering),
            (t.search.cache_sorting_config = v(t))),
            (t.search.tags_match = n.tags_match),
            n.brands &&
              ((t.search.brands = _.cloneDeep(n.brands)),
              (t.search.active_brands_count = _.filter(t.search.brands, {
                is_active: !0,
              }).length)),
            n.tags &&
              ((t.search.tags = _.cloneDeep(n.tags)),
              (t.search.active_tags_count = _.filter(t.search.tags, {
                is_active: !0,
              }).length)),
            n.blacklisted_tags &&
              ((t.search.blacklisted_tags = _.cloneDeep(n.blacklisted_tags)),
              (t.search.active_blacklisted_tags_count = _.filter(
                t.search.blacklisted_tags,
                { is_active: !0 },
              ).length)));
        },
        saveUserSearchState: function (t) {
          return m.apply(this, arguments);
        },
        order_options: [
          {
            text: "Recent Upload",
            order_by: "created_at",
            ordering: "desc",
            is_active: !0,
          },
          {
            text: "Old Upload",
            order_by: "created_at",
            ordering: "asc",
            is_active: !1,
          },
          {
            text: "Most Views",
            order_by: "views",
            ordering: "desc",
            is_active: !1,
          },
          {
            text: "Least Views",
            order_by: "views",
            ordering: "asc",
            is_active: !1,
          },
          {
            text: "Most Likes",
            order_by: "likes",
            ordering: "desc",
            is_active: !1,
          },
          {
            text: "Least Likes",
            order_by: "likes",
            ordering: "asc",
            is_active: !1,
          },
          {
            text: "Newest",
            order_by: "released_at",
            ordering: "desc",
            is_active: !1,
          },
          {
            text: "Oldest",
            order_by: "released_at",
            ordering: "asc",
            is_active: !1,
          },
          {
            text: "Alphabetical (A-Z)",
            order_by: "name",
            ordering: "asc",
            is_active: !1,
          },
          {
            text: "Alphabetical (Z-A)",
            order_by: "name",
            ordering: "desc",
            is_active: !1,
          },
        ],
        brands: [
          { text: "@ OZ", id: 64, is_active: !1 },
          { text: "37c-Binetsu", id: 1, is_active: !1 },
          { text: "Adult Source Media", id: 108, is_active: !1 },
          { text: "AIC", id: 190, is_active: !1 },
          { text: "Ajia-Do", id: 182, is_active: !1 },
          { text: "Almond Collective", id: 2, is_active: !1 },
          { text: "Alpha Polis", id: 185, is_active: !1 },
          { text: "Ameliatie", id: 114, is_active: !1 },
          { text: "Amour", id: 3, is_active: !1 },
          { text: "Animac", id: 4, is_active: !1 },
          { text: "Antechinus", id: 141, is_active: !1 },
          { text: "APPP", id: 137, is_active: !1 },
          { text: "Arms", id: 5, is_active: !1 },
          { text: "Bishop", id: 124, is_active: !1 },
          { text: "Blue Eyes", id: 6, is_active: !1 },
          { text: "BOMB! CUTE! BOMB!", id: 127, is_active: !1 },
          { text: "Bootleg", id: 7, is_active: !1 },
          { text: "BreakBottle", id: 8, is_active: !1 },
          { text: "BugBug", id: 9, is_active: !1 },
          { text: "Bunnywalker", id: 10, is_active: !1 },
          { text: "Celeb", id: 11, is_active: !1 },
          { text: "Central Park Media", id: 12, is_active: !1 },
          { text: "ChiChinoya", id: 13, is_active: !1 },
          { text: "Chocolat", id: 135, is_active: !1 },
          { text: "ChuChu", id: 14, is_active: !1 },
          { text: "Circle Tribute", id: 15, is_active: !1 },
          { text: "CoCoans", id: 16, is_active: !1 },
          { text: "Collaboration Works", id: 17, is_active: !1 },
          { text: "Comet", id: 109, is_active: !1 },
          { text: "Comic Media", id: 112, is_active: !1 },
          { text: "Cosmos", id: 18, is_active: !1 },
          { text: "Cranberry", id: 19, is_active: !1 },
          { text: "Crimson", id: 20, is_active: !1 },
          { text: "D3", id: 21, is_active: !1 },
          { text: "Daiei", id: 22, is_active: !1 },
          { text: "demodemon", id: 23, is_active: !1 },
          { text: "Digital Works", id: 24, is_active: !1 },
          { text: "Discovery", id: 25, is_active: !1 },
          { text: "Dollhouse", id: 128, is_active: !1 },
          { text: "EBIMARU-DO", id: 26, is_active: !1 },
          { text: "Echo", id: 27, is_active: !1 },
          { text: "ECOLONUN", id: 28, is_active: !1 },
          { text: "Edge", id: 29, is_active: !1 },
          { text: "Erozuki", id: 30, is_active: !1 },
          { text: "evee", id: 31, is_active: !1 },
          { text: "Fanza", id: 194, is_active: !1 },
          { text: "FINAL FUCK 7", id: 32, is_active: !1 },
          { text: "Five Ways", id: 33, is_active: !1 },
          { text: "Friends Media Station", id: 133, is_active: !1 },
          { text: "Front Line", id: 34, is_active: !1 },
          { text: "fruit", id: 35, is_active: !1 },
          { text: "Godoy", id: 187, is_active: !1 },
          { text: "GOLD BEAR", id: 36, is_active: !1 },
          { text: "gomasioken", id: 37, is_active: !1 },
          { text: "Green Bunny", id: 38, is_active: !1 },
          { text: "Groover", id: 179, is_active: !1 },
          { text: "Hoods Entertainment", id: 39, is_active: !1 },
          { text: "Hot Bear", id: 40, is_active: !1 },
          { text: "Hykobo", id: 41, is_active: !1 },
          { text: "IRONBELL", id: 119, is_active: !1 },
          { text: "Ivory Tower", id: 116, is_active: !1 },
          { text: "J.C.", id: 178, is_active: !1 },
          { text: "Jellyfish", id: 42, is_active: !1 },
          { text: "Jewel", id: 136, is_active: !1 },
          { text: "Juicy Mango", id: 195, is_active: !1 },
          { text: "Jumondo", id: 43, is_active: !1 },
          { text: "kate_sai", id: 44, is_active: !1 },
          { text: "KENZsoft", id: 45, is_active: !1 },
          { text: "King Bee", id: 46, is_active: !1 },
          { text: "Kitty Media", id: 132, is_active: !1 },
          { text: "Knack", id: 47, is_active: !1 },
          { text: "KoaLa", id: 191, is_active: !1 },
          { text: "Kuril", id: 48, is_active: !1 },
          { text: "L.", id: 49, is_active: !1 },
          { text: "Lemon Heart", id: 50, is_active: !1 },
          { text: "Lilix", id: 51, is_active: !1 },
          { text: "Lune Pictures", id: 52, is_active: !1 },
          { text: "Magic Bus", id: 53, is_active: !1 },
          { text: "Magin Label", id: 54, is_active: !1 },
          { text: "Majin Petit", id: 130, is_active: !1 },
          { text: "Marigold", id: 55, is_active: !1 },
          { text: "Mary Jane", id: 56, is_active: !1 },
          { text: "Media Blasters", id: 58, is_active: !1 },
          { text: "MediaBank", id: 57, is_active: !1 },
          { text: "Metro Notes", id: 142, is_active: !1 },
          { text: "Milky", id: 134, is_active: !1 },
          { text: "MiMiA Cute", id: 143, is_active: !1 },
          { text: "Moon Rock", id: 59, is_active: !1 },
          { text: "Moonstone Cherry", id: 60, is_active: !1 },
          { text: "Mousou Senka", id: 184, is_active: !1 },
          { text: "MS Pictures", id: 61, is_active: !1 },
          { text: "Muse", id: 126, is_active: !1 },
          { text: "N43", id: 139, is_active: !1 },
          { text: "Nihikime no Dozeu", id: 62, is_active: !1 },
          { text: "Nikkatsu Video", id: 177, is_active: !1 },
          { text: "nur", id: 131, is_active: !1 },
          { text: "NuTech Digital", id: 63, is_active: !1 },
          { text: "Obtain Future", id: 111, is_active: !1 },
          { text: "Otodeli", id: 120, is_active: !1 },
          { text: "Pashmina", id: 65, is_active: !1 },
          { text: "Passione", id: 186, is_active: !1 },
          { text: "Pastel", id: 192, is_active: !1 },
          { text: "Peach Pie", id: 123, is_active: !1 },
          { text: "Pink Pineapple", id: 67, is_active: !1 },
          { text: "Pinkbell", id: 66, is_active: !1 },
          { text: "Pix", id: 122, is_active: !1 },
          { text: "Pixy Soft", id: 68, is_active: !1 },
          { text: "Pocomo Premium", id: 69, is_active: !1 },
          { text: "PoRO", id: 70, is_active: !1 },
          { text: "Project No.9", id: 71, is_active: !1 },
          { text: "Queen Bee", id: 72, is_active: !1 },
          { text: "Rabbit Gate", id: 73, is_active: !1 },
          { text: "ROJIURA JACK", id: 188, is_active: !1 },
          { text: "sakamotoJ", id: 74, is_active: !1 },
          { text: "Sakura Purin", id: 138, is_active: !1 },
          { text: "SANDWICHWORKS", id: 75, is_active: !1 },
          { text: "Schoolzone", id: 76, is_active: !1 },
          { text: "seismic", id: 77, is_active: !1 },
          { text: "SELFISH", id: 78, is_active: !1 },
          { text: "Seven", id: 79, is_active: !1 },
          { text: "Shadow Prod. Co.", id: 80, is_active: !1 },
          { text: "Shelf", id: 107, is_active: !1 },
          { text: "Shinyusha", id: 81, is_active: !1 },
          { text: "ShoSai", id: 110, is_active: !1 },
          { text: "Showten", id: 82, is_active: !1 },
          { text: "Soft on Demand", id: 83, is_active: !1 },
          { text: "SoftCell", id: 176, is_active: !1 },
          { text: "SPEED", id: 125, is_active: !1 },
          { text: "STARGATE3D", id: 84, is_active: !1 },
          { text: "Studio 9 Maiami", id: 85, is_active: !1 },
          { text: "Studio Akai Shohosen", id: 86, is_active: !1 },
          { text: "Studio Deen", id: 87, is_active: !1 },
          { text: "Studio Fantasia", id: 88, is_active: !1 },
          { text: "Studio FOW", id: 89, is_active: !1 },
          { text: "studio GGB", id: 90, is_active: !1 },
          { text: "Studio Gokumi", id: 189, is_active: !1 },
          { text: "Studio Houkiboshi", id: 140, is_active: !1 },
          { text: "Studio Zealot", id: 91, is_active: !1 },
          { text: "Suiseisha", id: 115, is_active: !1 },
          { text: "SurviveMore", id: 197, is_active: !1 },
          { text: "Suzuki Mirano", id: 92, is_active: !1 },
          { text: "SYLD", id: 93, is_active: !1 },
          { text: "T-Rex", id: 96, is_active: !1 },
          { text: "t japan", id: 118, is_active: !1 },
          { text: "TDK Core", id: 113, is_active: !1 },
          { text: "TNK", id: 183, is_active: !1 },
          { text: "TOHO", id: 94, is_active: !1 },
          { text: "Toranoana", id: 95, is_active: !1 },
          { text: "Torudaya", id: 193, is_active: !1 },
          { text: "Triangle", id: 129, is_active: !1 },
          { text: "Trimax", id: 117, is_active: !1 },
          { text: "TYS Work", id: 97, is_active: !1 },
          { text: "U-Jin", id: 181, is_active: !1 },
          { text: "Umemaro-3D", id: 98, is_active: !1 },
          { text: "Union Cho", id: 99, is_active: !1 },
          { text: "Valkyria", id: 100, is_active: !1 },
          { text: "Vanilla", id: 101, is_active: !1 },
          { text: "White Bear", id: 102, is_active: !1 },
        ],
        tags: [
          { text: "3d", id: 449, is_active: !1 },
          { text: "Ahegao", id: 115, is_active: !1 },
          { text: "Anal", id: 40, is_active: !1 },
          { text: "Bdsm", id: 522, is_active: !1 },
          { text: "Big Boobs", id: 110, is_active: !1 },
          { text: "Blow Job", id: 169, is_active: !1 },
          { text: "Bondage", id: 26, is_active: !1 },
          { text: "Boob Job", id: 475, is_active: !1 },
          { text: "Censored", id: 523, is_active: !1 },
          { text: "Comedy", id: 147, is_active: !1 },
          { text: "Cosplay", id: 32, is_active: !1 },
          { text: "Creampie", id: 20, is_active: !1 },
          { text: "Dark Skin", id: 104, is_active: !1 },
          { text: "Facial", id: 219, is_active: !1 },
          { text: "Fantasy", id: 501, is_active: !1 },
          { text: "Filmed", id: 220, is_active: !1 },
          { text: "Foot Job", id: 314, is_active: !1 },
          { text: "Futanari", id: 179, is_active: !1 },
          { text: "Gangbang", id: 168, is_active: !1 },
          { text: "Glasses", id: 521, is_active: !1 },
          { text: "Hand Job", id: 463, is_active: !1 },
          { text: "Harem", id: 23, is_active: !1 },
          { text: "Hd", id: 499, is_active: !1 },
          { text: "Horror", id: 12, is_active: !1 },
          { text: "Incest", id: 6, is_active: !1 },
          { text: "Inflation", id: 444, is_active: !1 },
          { text: "Lactation", id: 244, is_active: !1 },
          { text: "Maid", id: 56, is_active: !1 },
          { text: "Masturbation", id: 524, is_active: !1 },
          { text: "Milf", id: 245, is_active: !1 },
          { text: "Mind Break", id: 265, is_active: !1 },
          { text: "Mind Control", id: 241, is_active: !1 },
          { text: "Monster", id: 75, is_active: !1 },
          { text: "Nekomimi", id: 527, is_active: !1 },
          { text: "Ntr", id: 261, is_active: !1 },
          { text: "Nurse", id: 78, is_active: !1 },
          { text: "Orgy", id: 255, is_active: !1 },
          { text: "Plot", id: 266, is_active: !1 },
          { text: "Pov", id: 25, is_active: !1 },
          { text: "Pregnant", id: 61, is_active: !1 },
          { text: "Public Sex", id: 223, is_active: !1 },
          { text: "Rimjob", id: 372, is_active: !1 },
          { text: "Scat", id: 299, is_active: !1 },
          { text: "School Girl", id: 256, is_active: !1 },
          { text: "Softcore", id: 520, is_active: !1 },
          { text: "Swimsuit", id: 35, is_active: !1 },
          { text: "Teacher", id: 50, is_active: !1 },
          { text: "Tentacle", id: 15, is_active: !1 },
          { text: "Threesome", id: 525, is_active: !1 },
          { text: "Toys", id: 469, is_active: !1 },
          { text: "Trap", id: 341, is_active: !1 },
          { text: "Tsundere", id: 257, is_active: !1 },
          { text: "Ugly Bastard", id: 514, is_active: !1 },
          { text: "Uncensored", id: 28, is_active: !1 },
          { text: "Vanilla", id: 190, is_active: !1 },
          { text: "Virgin", id: 30, is_active: !1 },
          { text: "Watersports", id: 274, is_active: !1 },
          { text: "X-Ray", id: 526, is_active: !1 },
          { text: "Yaoi", id: 97, is_active: !1 },
          { text: "Yuri", id: 3, is_active: !1 },
        ],
        blacklisted_tags: [
          { text: "3d", id: 449, is_active: !1 },
          { text: "Ahegao", id: 115, is_active: !1 },
          { text: "Anal", id: 40, is_active: !1 },
          { text: "Bdsm", id: 522, is_active: !1 },
          { text: "Big Boobs", id: 110, is_active: !1 },
          { text: "Blow Job", id: 169, is_active: !1 },
          { text: "Bondage", id: 26, is_active: !1 },
          { text: "Boob Job", id: 475, is_active: !1 },
          { text: "Censored", id: 523, is_active: !1 },
          { text: "Comedy", id: 147, is_active: !1 },
          { text: "Cosplay", id: 32, is_active: !1 },
          { text: "Creampie", id: 20, is_active: !1 },
          { text: "Dark Skin", id: 104, is_active: !1 },
          { text: "Facial", id: 219, is_active: !1 },
          { text: "Fantasy", id: 501, is_active: !1 },
          { text: "Filmed", id: 220, is_active: !1 },
          { text: "Foot Job", id: 314, is_active: !1 },
          { text: "Futanari", id: 179, is_active: !1 },
          { text: "Gangbang", id: 168, is_active: !1 },
          { text: "Glasses", id: 521, is_active: !1 },
          { text: "Hand Job", id: 463, is_active: !1 },
          { text: "Harem", id: 23, is_active: !1 },
          { text: "Hd", id: 499, is_active: !1 },
          { text: "Horror", id: 12, is_active: !1 },
          { text: "Incest", id: 6, is_active: !1 },
          { text: "Inflation", id: 444, is_active: !1 },
          { text: "Lactation", id: 244, is_active: !1 },
          { text: "Maid", id: 56, is_active: !1 },
          { text: "Masturbation", id: 524, is_active: !1 },
          { text: "Milf", id: 245, is_active: !1 },
          { text: "Mind Break", id: 265, is_active: !1 },
          { text: "Mind Control", id: 241, is_active: !1 },
          { text: "Monster", id: 75, is_active: !1 },
          { text: "Nekomimi", id: 527, is_active: !1 },
          { text: "Ntr", id: 261, is_active: !1 },
          { text: "Nurse", id: 78, is_active: !1 },
          { text: "Orgy", id: 255, is_active: !1 },
          { text: "Plot", id: 266, is_active: !1 },
          { text: "Pov", id: 25, is_active: !1 },
          { text: "Pregnant", id: 61, is_active: !1 },
          { text: "Public Sex", id: 223, is_active: !1 },
          { text: "Rimjob", id: 372, is_active: !1 },
          { text: "Scat", id: 299, is_active: !1 },
          { text: "School Girl", id: 256, is_active: !1 },
          { text: "Softcore", id: 520, is_active: !1 },
          { text: "Swimsuit", id: 35, is_active: !1 },
          { text: "Teacher", id: 50, is_active: !1 },
          { text: "Tentacle", id: 15, is_active: !1 },
          { text: "Threesome", id: 525, is_active: !1 },
          { text: "Toys", id: 469, is_active: !1 },
          { text: "Trap", id: 341, is_active: !1 },
          { text: "Tsundere", id: 257, is_active: !1 },
          { text: "Ugly Bastard", id: 514, is_active: !1 },
          { text: "Uncensored", id: 28, is_active: !1 },
          { text: "Vanilla", id: 190, is_active: !1 },
          { text: "Virgin", id: 30, is_active: !1 },
          { text: "Watersports", id: 274, is_active: !1 },
          { text: "X-Ray", id: 526, is_active: !1 },
          { text: "Yaoi", id: 97, is_active: !1 },
          { text: "Yuri", id: 3, is_active: !1 },
        ],
      };
    },
    44: function (t, n, r) {
      "use strict";
      (r.d(n, "b", function () {
        return pt;
      }),
        r.d(n, "a", function () {
          return I;
        }));
      (r(28), r(20), r(35), r(36), r(29), r(30));
      var o = r(5),
        c = r(17),
        l = (r(27), r(53), r(22), r(14), r(19), r(61), r(1)),
        d = r.n(l),
        h = r(218),
        f = r(156),
        v = r.n(f),
        m = r(76),
        x = r.n(m),
        y = (r(40), r(47), r(157)),
        w = r.n(y),
        $ = r(43),
        O = r(0);
      r(276);
      "scrollRestoration" in window.history &&
        (Object(O.u)("manual"),
        window.addEventListener("beforeunload", function () {
          Object(O.u)("auto");
        }),
        window.addEventListener("load", function () {
          Object(O.u)("manual");
        }));
      function E(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function C(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? E(Object(n), !0).forEach(function (e) {
                Object(c.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : E(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      var k = function () {};
      d.a.use(w.a);
      var T = {
        mode: "history",
        base: "/",
        linkActiveClass: "nuxt-link-active",
        linkExactActiveClass: "nuxt-link-exact-active",
        scrollBehavior: function (t, e, n) {
          var r = !1,
            o = t !== e;
          n
            ? (r = n)
            : o &&
              (function (t) {
                var e = Object(O.g)(t);
                if (1 === e.length) {
                  var n = e[0].options;
                  return !1 !== (void 0 === n ? {} : n).scrollToTop;
                }
                return e.some(function (t) {
                  var e = t.options;
                  return e && e.scrollToTop;
                });
              })(t) &&
              (r = { x: 0, y: 0 });
          var c = window.$nuxt;
          return (
            (!o || (t.path === e.path && t.hash !== e.hash)) &&
              c.$nextTick(function () {
                return c.$emit("triggerScroll");
              }),
            new Promise(function (e) {
              c.$once("triggerScroll", function () {
                if (t.hash) {
                  var n = t.hash;
                  void 0 !== window.CSS &&
                    void 0 !== window.CSS.escape &&
                    (n = "#" + window.CSS.escape(n.substr(1)));
                  try {
                    var o = document.querySelector(n);
                    if (o) {
                      var c;
                      r = { selector: n };
                      var l = Number(
                        null ===
                          (c = getComputedStyle(o)["scroll-margin-top"]) ||
                          void 0 === c
                          ? void 0
                          : c.replace("px", ""),
                      );
                      l && (r.offset = { y: l });
                    }
                  } catch (t) {
                    console.warn(
                      "Failed to save scroll position. Please add CSS.escape() polyfill (https://github.com/mathiasbynens/CSS.escape).",
                    );
                  }
                }
                e(r);
              });
            })
          );
        },
        routes: [
          {
            path: "/2257",
            component: function () {
              return Object(O.m)(r.e(3).then(r.bind(null, 347)));
            },
            name: "2257",
          },
          {
            path: "/404",
            component: function () {
              return Object(O.m)(r.e(4).then(r.bind(null, 348)));
            },
            name: "404",
          },
          {
            path: "/account",
            component: function () {
              return Object(O.m)(r.e(6).then(r.bind(null, 349)));
            },
            name: "account",
          },
          {
            path: "/apps",
            component: function () {
              return Object(O.m)(r.e(9).then(r.bind(null, 350)));
            },
            name: "apps",
          },
          {
            path: "/auth",
            component: function () {
              return Object(O.m)(r.e(10).then(r.bind(null, 351)));
            },
            name: "auth",
          },
          {
            path: "/browse",
            component: function () {
              return Object(O.m)(r.e(15).then(r.bind(null, 352)));
            },
            name: "browse",
          },
          {
            path: "/business",
            component: function () {
              return Object(O.m)(r.e(21).then(r.bind(null, 353)));
            },
            name: "business",
          },
          {
            path: "/channel",
            component: function () {
              return Object(O.m)(
                Promise.all([r.e(0), r.e(22)]).then(r.bind(null, 354)),
              );
            },
            name: "channel",
          },
          {
            path: "/chat",
            component: function () {
              return Object(O.m)(r.e(25).then(r.bind(null, 355)));
            },
            name: "chat",
          },
          {
            path: "/cookies",
            component: function () {
              return Object(O.m)(r.e(26).then(r.bind(null, 356)));
            },
            name: "cookies",
          },
          {
            path: "/create-account",
            component: function () {
              return Object(O.m)(r.e(27).then(r.bind(null, 357)));
            },
            name: "create-account",
          },
          {
            path: "/edit-my-channel",
            component: function () {
              return Object(O.m)(r.e(30).then(r.bind(null, 358)));
            },
            name: "edit-my-channel",
          },
          {
            path: "/eula",
            component: function () {
              return Object(O.m)(r.e(31).then(r.bind(null, 359)));
            },
            name: "eula",
          },
          {
            path: "/forgot-password",
            component: function () {
              return Object(O.m)(r.e(32).then(r.bind(null, 360)));
            },
            name: "forgot-password",
          },
          {
            path: "/home",
            component: function () {
              return Object(O.m)(r.e(35).then(r.bind(null, 361)));
            },
            name: "home",
          },
          {
            path: "/inter",
            component: function () {
              return Object(O.m)(r.e(37).then(r.bind(null, 362)));
            },
            name: "inter",
          },
          {
            path: "/open-source-notices",
            component: function () {
              return Object(O.m)(r.e(38).then(r.bind(null, 363)));
            },
            name: "open-source-notices",
          },
          {
            path: "/premium",
            component: function () {
              return Object(O.m)(r.e(43).then(r.bind(null, 364)));
            },
            name: "premium",
          },
          {
            path: "/prior-terms",
            component: function () {
              return Object(O.m)(r.e(44).then(r.bind(null, 365)));
            },
            name: "prior-terms",
          },
          {
            path: "/privacy",
            component: function () {
              return Object(O.m)(r.e(45).then(r.bind(null, 366)));
            },
            name: "privacy",
          },
          {
            path: "/prohibited",
            component: function () {
              return Object(O.m)(r.e(46).then(r.bind(null, 367)));
            },
            name: "prohibited",
          },
          {
            path: "/search",
            component: function () {
              return Object(O.m)(r.e(47).then(r.bind(null, 368)));
            },
            name: "search",
          },
          {
            path: "/sign-in",
            component: function () {
              return Object(O.m)(r.e(48).then(r.bind(null, 369)));
            },
            name: "sign-in",
          },
          {
            path: "/terms-of-use",
            component: function () {
              return Object(O.m)(r.e(49).then(r.bind(null, 370)));
            },
            name: "terms-of-use",
          },
          {
            path: "/browse/images",
            component: function () {
              return Object(O.m)(r.e(14).then(r.bind(null, 371)));
            },
            name: "browse-images",
          },
          {
            path: "/browse/random",
            component: function () {
              return Object(O.m)(r.e(16).then(r.bind(null, 372)));
            },
            name: "browse-random",
          },
          {
            path: "/browse/seasons",
            component: function () {
              return Object(O.m)(r.e(17).then(r.bind(null, 373)));
            },
            name: "browse-seasons",
          },
          {
            path: "/browse/trending",
            component: function () {
              return Object(O.m)(r.e(20).then(r.bind(null, 374)));
            },
            name: "browse-trending",
          },
          {
            path: "/",
            component: function () {
              return Object(O.m)(r.e(36).then(r.bind(null, 375)));
            },
            name: "index",
          },
          {
            path: "/browse/brands/:id",
            component: function () {
              return Object(O.m)(r.e(13).then(r.bind(null, 376)));
            },
            name: "browse-brands-id",
          },
          {
            path: "/browse/tags/:id",
            component: function () {
              return Object(O.m)(r.e(19).then(r.bind(null, 377)));
            },
            name: "browse-tags-id",
          },
          {
            path: "/videos/hentai/:id?",
            component: function () {
              return Object(O.m)(
                Promise.all([r.e(53), r.e(50)]).then(r.bind(null, 378)),
              );
            },
            name: "videos-hentai-id",
          },
          {
            path: "/browse/tags/*",
            component: function () {
              return Object(O.m)(r.e(18).then(r.bind(null, 379)));
            },
            name: "browse-tags-all",
          },
          {
            path: "/browse/brands/*",
            component: function () {
              return Object(O.m)(r.e(12).then(r.bind(null, 380)));
            },
            name: "browse-brands-all",
          },
          {
            path: "/account-deletion/:id",
            component: function () {
              return Object(O.m)(r.e(8).then(r.bind(null, 381)));
            },
            name: "account-deletion-id",
          },
          {
            path: "/channels/:id",
            component: function () {
              return Object(O.m)(
                Promise.all([r.e(0), r.e(24)]).then(r.bind(null, 382)),
              );
            },
            name: "channels-id",
          },
          {
            path: "/downloads/:id",
            component: function () {
              return Object(O.m)(r.e(29).then(r.bind(null, 383)));
            },
            name: "downloads-id",
          },
          {
            path: "/hentai-videos/:id",
            component: function () {
              return Object(O.m)(r.e(34).then(r.bind(null, 384)));
            },
            name: "hentai-videos-id",
          },
          {
            path: "/password-resets/:id",
            component: function () {
              return Object(O.m)(r.e(40).then(r.bind(null, 385)));
            },
            name: "password-resets-id",
          },
          {
            path: "/playlists/:id",
            component: function () {
              return Object(O.m)(r.e(42).then(r.bind(null, 386)));
            },
            name: "playlists-id",
          },
          {
            path: "/playlists/*",
            component: function () {
              return Object(O.m)(r.e(41).then(r.bind(null, 387)));
            },
            name: "playlists-all",
          },
          {
            path: "/password-resets/*",
            component: function () {
              return Object(O.m)(r.e(39).then(r.bind(null, 388)));
            },
            name: "password-resets-all",
          },
          {
            path: "/hentai-videos/*",
            component: function () {
              return Object(O.m)(r.e(33).then(r.bind(null, 389)));
            },
            name: "hentai-videos-all",
          },
          {
            path: "/downloads/*",
            component: function () {
              return Object(O.m)(r.e(28).then(r.bind(null, 390)));
            },
            name: "downloads-all",
          },
          {
            path: "/channels/*",
            component: function () {
              return Object(O.m)(r.e(23).then(r.bind(null, 391)));
            },
            name: "channels-all",
          },
          {
            path: "/browse/*",
            component: function () {
              return Object(O.m)(r.e(11).then(r.bind(null, 392)));
            },
            name: "browse-all",
          },
          {
            path: "/account-deletion/*",
            component: function () {
              return Object(O.m)(r.e(7).then(r.bind(null, 393)));
            },
            name: "account-deletion-all",
          },
          {
            path: "/*",
            component: function () {
              return Object(O.m)(r.e(5).then(r.bind(null, 394)));
            },
            name: "all",
          },
        ],
        fallback: !1,
      };
      function A(t, e) {
        var base = (e._app && e._app.basePath) || T.base,
          n = new w.a(C(C({}, T), {}, { base: base })),
          r = n.push;
        n.push = function (t) {
          var e =
              arguments.length > 1 && void 0 !== arguments[1]
                ? arguments[1]
                : k,
            n = arguments.length > 2 ? arguments[2] : void 0;
          return r.call(this, t, e, n);
        };
        var o = n.resolve.bind(n);
        return (
          (n.resolve = function (t, e, n) {
            return ("string" == typeof t && (t = Object($.c)(t)), o(t, e, n));
          }),
          n
        );
      }
      var j = {
          name: "NuxtChild",
          functional: !0,
          props: {
            nuxtChildKey: { type: String, default: "" },
            keepAlive: Boolean,
            keepAliveProps: { type: Object, default: void 0 },
          },
          render: function (t, e) {
            var n = e.parent,
              data = e.data,
              r = e.props,
              o = n.$createElement;
            data.nuxtChild = !0;
            for (
              var c = n,
                l = n.$nuxt.nuxt.transitions,
                d = n.$nuxt.nuxt.defaultTransition,
                _ = 0;
              n;
            )
              (n.$vnode && n.$vnode.data.nuxtChild && _++, (n = n.$parent));
            data.nuxtChildDepth = _;
            var h = l[_] || d,
              f = {};
            R.forEach(function (t) {
              void 0 !== h[t] && (f[t] = h[t]);
            });
            var v = {};
            P.forEach(function (t) {
              "function" == typeof h[t] && (v[t] = h[t].bind(c));
            });
            var m = v.beforeEnter;
            if (
              ((v.beforeEnter = function (t) {
                if (
                  (window.$nuxt.$nextTick(function () {
                    window.$nuxt.$emit("triggerScroll");
                  }),
                  m)
                )
                  return m.call(c, t);
              }),
              !1 === h.css)
            ) {
              var x = v.leave;
              (!x || x.length < 2) &&
                (v.leave = function (t, e) {
                  (x && x.call(c, t), c.$nextTick(e));
                });
            }
            var y = o("routerView", data);
            return (
              r.keepAlive &&
                (y = o("keep-alive", { props: r.keepAliveProps }, [y])),
              o("transition", { props: f, on: v }, [y])
            );
          },
        },
        R = [
          "name",
          "mode",
          "appear",
          "css",
          "type",
          "duration",
          "enterClass",
          "leaveClass",
          "appearClass",
          "enterActiveClass",
          "enterActiveClass",
          "leaveActiveClass",
          "appearActiveClass",
          "enterToClass",
          "leaveToClass",
          "appearToClass",
        ],
        P = [
          "beforeEnter",
          "enter",
          "afterEnter",
          "enterCancelled",
          "beforeLeave",
          "leave",
          "afterLeave",
          "leaveCancelled",
          "beforeAppear",
          "appear",
          "afterAppear",
          "appearCancelled",
        ],
        D = {
          name: "nuxt-error",
          props: ["error"],
          head: function () {
            return { title: this.error.message || "An error occured" };
          },
        },
        N = r(15),
        I = Object(N.a)(
          D,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "div",
              {
                staticClass:
                  "full-page-height flex justify-center align-center",
              },
              [
                e("div", [
                  e("h1", { staticClass: "error-code" }, [
                    t._v(t._s(t.error.statusCode)),
                  ]),
                  t._v(" "),
                  e("div", { staticClass: "error-wrapper-message" }, [
                    e("h2", { staticClass: "error-message" }, [
                      t._v(t._s(t.error.message)),
                    ]),
                  ]),
                  t._v(" "),
                  404 === t.error.statusCode
                    ? e(
                        "p",
                        [
                          e(
                            "router-link",
                            { staticClass: "error-link", attrs: { to: "/" } },
                            [t._v("\n        Back to the home page\n      ")],
                          ),
                        ],
                        1,
                      )
                    : t._e(),
                ]),
              ],
            );
          },
          [],
          !1,
          null,
          null,
          null,
        ).exports,
        L = r(21),
        V =
          (r(39),
          {
            name: "Nuxt",
            components: { NuxtChild: j, NuxtError: I },
            props: {
              nuxtChildKey: { type: String, default: void 0 },
              keepAlive: Boolean,
              keepAliveProps: { type: Object, default: void 0 },
              name: { type: String, default: "default" },
            },
            errorCaptured: function (t) {
              this.displayingNuxtError &&
                ((this.errorFromNuxtError = t), this.$forceUpdate());
            },
            computed: {
              routerViewKey: function () {
                if (
                  void 0 !== this.nuxtChildKey ||
                  this.$route.matched.length > 1
                )
                  return (
                    this.nuxtChildKey ||
                    Object(O.c)(this.$route.matched[0].path)(this.$route.params)
                  );
                var t = Object(L.a)(this.$route.matched, 1)[0];
                if (!t) return this.$route.path;
                var e = t.components.default;
                if (e && e.options) {
                  var n = e.options;
                  if (n.key)
                    return "function" == typeof n.key
                      ? n.key(this.$route)
                      : n.key;
                }
                return /\/$/.test(t.path)
                  ? this.$route.path
                  : this.$route.path.replace(/\/$/, "");
              },
            },
            beforeCreate: function () {
              d.a.util.defineReactive(this, "nuxt", this.$root.$options.nuxt);
            },
            render: function (t) {
              var e = this;
              return this.nuxt.err && this.nuxt.errPageReady
                ? this.errorFromNuxtError
                  ? (this.$nextTick(function () {
                      return (e.errorFromNuxtError = !1);
                    }),
                    t("div", {}, [
                      t("h2", "An error occurred while showing the error page"),
                      t(
                        "p",
                        "Unfortunately an error occurred and while showing the error page another error occurred",
                      ),
                      t(
                        "p",
                        "Error details: ".concat(
                          this.errorFromNuxtError.toString(),
                        ),
                      ),
                      t("nuxt-link", { props: { to: "/" } }, "Go back to home"),
                    ]))
                  : ((this.displayingNuxtError = !0),
                    this.$nextTick(function () {
                      return (e.displayingNuxtError = !1);
                    }),
                    t(I, { props: { error: this.nuxt.err } }))
                : t("NuxtChild", {
                    key: this.routerViewKey,
                    props: this.$props,
                  });
            },
          }),
        U =
          (r(77),
          r(79),
          r(80),
          r(54),
          r(62),
          {
            name: "NuxtLoading",
            data: function () {
              return {
                percent: 0,
                show: !1,
                canSucceed: !0,
                reversed: !1,
                skipTimerCount: 0,
                rtl: !1,
                throttle: 200,
                duration: 5e3,
                continuous: !1,
              };
            },
            computed: {
              left: function () {
                return (
                  !(!this.continuous && !this.rtl) &&
                  (this.rtl
                    ? this.reversed
                      ? "0px"
                      : "auto"
                    : this.reversed
                      ? "auto"
                      : "0px")
                );
              },
            },
            beforeDestroy: function () {
              this.clear();
            },
            methods: {
              clear: function () {
                (clearInterval(this._timer),
                  clearTimeout(this._throttle),
                  clearTimeout(this._hide),
                  (this._timer = null));
              },
              start: function () {
                var t = this;
                return (
                  this.clear(),
                  (this.percent = 0),
                  (this.reversed = !1),
                  (this.skipTimerCount = 0),
                  (this.canSucceed = !0),
                  this.throttle
                    ? (this._throttle = setTimeout(function () {
                        return t.startTimer();
                      }, this.throttle))
                    : this.startTimer(),
                  this
                );
              },
              set: function (t) {
                return (
                  (this.show = !0),
                  (this.canSucceed = !0),
                  (this.percent = Math.min(100, Math.max(0, Math.floor(t)))),
                  this
                );
              },
              get: function () {
                return this.percent;
              },
              increase: function (t) {
                return (
                  (this.percent = Math.min(100, Math.floor(this.percent + t))),
                  this
                );
              },
              decrease: function (t) {
                return (
                  (this.percent = Math.max(0, Math.floor(this.percent - t))),
                  this
                );
              },
              pause: function () {
                return (clearInterval(this._timer), this);
              },
              resume: function () {
                return (this.startTimer(), this);
              },
              finish: function () {
                return (
                  (this.percent = this.reversed ? 0 : 100),
                  this.hide(),
                  this
                );
              },
              hide: function () {
                var t = this;
                return (
                  this.clear(),
                  (this._hide = setTimeout(function () {
                    ((t.show = !1),
                      t.$nextTick(function () {
                        ((t.percent = 0), (t.reversed = !1));
                      }));
                  }, 500)),
                  this
                );
              },
              fail: function (t) {
                return ((this.canSucceed = !1), this);
              },
              startTimer: function () {
                var t = this;
                (this.show || (this.show = !0),
                  void 0 === this._cut &&
                    (this._cut = 1e4 / Math.floor(this.duration)),
                  (this._timer = setInterval(function () {
                    t.skipTimerCount > 0
                      ? t.skipTimerCount--
                      : (t.reversed ? t.decrease(t._cut) : t.increase(t._cut),
                        t.continuous &&
                          (t.percent >= 100 || t.percent <= 0) &&
                          ((t.skipTimerCount = 1), (t.reversed = !t.reversed)));
                  }, 100)));
              },
            },
            render: function (t) {
              var e = t(!1);
              return (
                this.show &&
                  (e = t("div", {
                    staticClass: "nuxt-progress",
                    class: {
                      "nuxt-progress-notransition": this.skipTimerCount > 0,
                      "nuxt-progress-failed": !this.canSucceed,
                    },
                    style: { width: this.percent + "%", left: this.left },
                  })),
                e
              );
            },
          }),
        M =
          (r(278),
          Object(N.a)(U, undefined, undefined, !1, null, null, null).exports),
        B = (r(81), r(154), r(63)),
        H = r(116),
        F = r(117);
      (r(45), r(159), r(121));
      function G(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function W(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? G(Object(n), !0).forEach(function (e) {
                Object(c.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : G(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      var K = (function () {
          return Object(F.a)(
            function t(e, n) {
              var r =
                arguments.length > 2 && void 0 !== arguments[2]
                  ? arguments[2]
                  : null;
              (Object(H.a)(this, t),
                (this.dataKey = null),
                (this.hentaiVideo = null),
                (this.nuxt_context = null),
                (this.dataKey = e),
                (this.hentaiVideo = n),
                (this.nuxt_context = r));
            },
            [
              {
                key: "showMore",
                value:
                  ((r = Object(o.a)(
                    regeneratorRuntime.mark(function t(n, r, o) {
                      var c,
                        l,
                        d,
                        _,
                        data,
                        h,
                        f,
                        v = arguments;
                      return regeneratorRuntime.wrap(
                        function (t) {
                          for (;;)
                            switch ((t.prev = t.next)) {
                              case 0:
                                if (
                                  ((c =
                                    v.length > 3 && void 0 !== v[3] && v[3]),
                                  !this.nuxt_context.$S.is_loading || c)
                                ) {
                                  t.next = 1;
                                  break;
                                }
                                return t.abrupt("return");
                              case 1:
                                return (
                                  (this.nuxt_context.$S.is_loading = !0),
                                  (l = null),
                                  o
                                    ? ((n.offset += n.page_size),
                                      (l = ""
                                        .concat(
                                          this.nuxt_context.$getApiBaseUrl(),
                                          "/api/v8/",
                                        )
                                        .concat(o, "s?")
                                        .concat(r, "_id=")
                                        .concat(n.userComment.id, "&order=")
                                        .concat(this.order(), "&offset=")
                                        .concat(n.offset, "&count=")
                                        .concat(n.page_size)))
                                    : ((this.nuxt_context.$S.data[
                                        this.dataKey
                                      ].offset +=
                                        this.nuxt_context.$S.data[
                                          this.dataKey
                                        ].page_size),
                                      (l = ""
                                        .concat(
                                          this.nuxt_context.$getApiBaseUrl(),
                                          "/api/v8/",
                                        )
                                        .concat(r, "s?hv_id=")
                                        .concat(this.hentaiVideo.id, "&order=")
                                        .concat(this.order(), "&offset=")
                                        .concat(
                                          this.nuxt_context.$S.data[
                                            this.dataKey
                                          ].offset,
                                          "&count=",
                                        )
                                        .concat(
                                          this.nuxt_context.$S.data[
                                            this.dataKey
                                          ].page_size,
                                        ))),
                                  (t.prev = 2),
                                  (t.next = 3),
                                  this.nuxt_context.$get(l)
                                );
                              case 3:
                                ((d = t.sent),
                                  (_ = d.response),
                                  (data = d.data),
                                  (h = { data: data, status: _.status }),
                                  this.fetchSuccess(h, n, o || r),
                                  (t.next = 5));
                                break;
                              case 4:
                                ((t.prev = 4), (f = t.catch(2)), e(f));
                              case 5:
                                this.nuxt_context.$S.is_loading = !1;
                              case 6:
                              case "end":
                                return t.stop();
                            }
                        },
                        t,
                        this,
                        [[2, 4]],
                      );
                    }),
                  )),
                  function (t, e, n) {
                    return r.apply(this, arguments);
                  }),
              },
              {
                key: "fetchSuccess",
                value:
                  ((n = Object(o.a)(
                    regeneratorRuntime.mark(function t(e, n, r) {
                      var data, o, c, l;
                      return regeneratorRuntime.wrap(
                        function (t) {
                          for (;;)
                            switch ((t.prev = t.next)) {
                              case 0:
                                ((data = e.data),
                                  (this.nuxt_context.$S.data[this.dataKey][
                                    "my_".concat(r, "s_flags_hash")
                                  ] = W(
                                    W(
                                      {},
                                      this.nuxt_context.$S.data[this.dataKey][
                                        "my_".concat(r, "s_flags_hash")
                                      ],
                                    ),
                                    data.meta["my_".concat(r, "s_flags_hash")],
                                  )),
                                  (this.nuxt_context.$S.data[this.dataKey][
                                    "my_".concat(r, "s_votes_hash")
                                  ] = W(
                                    W(
                                      {},
                                      this.nuxt_context.$S.data[this.dataKey][
                                        "my_".concat(r, "s_votes_hash")
                                      ],
                                    ),
                                    data.meta["my_".concat(r, "s_votes_hash")],
                                  )),
                                  (this.nuxt_context.$S.data[
                                    this.dataKey
                                  ].is_loading = !1),
                                  "hthread" == r
                                    ? ((o =
                                        this.nuxt_context.$S.data[this.dataKey]
                                          .hthreads).push.apply(
                                        o,
                                        Object(B.a)(data.data),
                                      ),
                                      (this.nuxt_context.$S.data[
                                        this.dataKey
                                      ].num_threads =
                                        data.meta.totals.num_threads),
                                      (this.nuxt_context.$S.data[
                                        this.dataKey
                                      ].num_comments =
                                        data.meta.totals.num_comments),
                                      (this.nuxt_context.$S.data[
                                        this.dataKey
                                      ].is_hthreads_initialized = !0),
                                      (this.nuxt_context.$S.data[
                                        this.dataKey
                                      ].state = "INITIALIZED"))
                                    : ((c = [].concat(
                                        Object(B.a)(n.child_comments),
                                        Object(B.a)(data.data),
                                      )),
                                      (l = _.uniqBy(c, "id")),
                                      (n.child_comments = l)),
                                  data.data.length > 0 &&
                                    this.updateUsersHash(data.data));
                              case 1:
                              case "end":
                                return t.stop();
                            }
                        },
                        t,
                        this,
                      );
                    }),
                  )),
                  function (t, e, r) {
                    return n.apply(this, arguments);
                  }),
              },
              {
                key: "updateUsersHash",
                value:
                  ((t = Object(o.a)(
                    regeneratorRuntime.mark(function t(n) {
                      var r,
                        o,
                        c,
                        l,
                        h = this;
                      return regeneratorRuntime.wrap(
                        function (t) {
                          for (;;)
                            switch ((t.prev = t.next)) {
                              case 0:
                                return (
                                  (r = []),
                                  _.each(n, function (t, e) {
                                    r.push(
                                      "user_ids[]=".concat(
                                        t.original_poster_user_id,
                                      ),
                                    );
                                  }),
                                  (r = r.join("&")),
                                  (t.prev = 1),
                                  (t.next = 2),
                                  this.nuxt_context.$get(
                                    ""
                                      .concat(
                                        this.nuxt_context.$getApiBaseUrl(),
                                        "/rapi/v7/users?source=comments&",
                                      )
                                      .concat(r),
                                  )
                                );
                              case 2:
                                ((o = t.sent),
                                  (c = o.data),
                                  _.each(c, function (t, e) {
                                    d.a.set(
                                      h.nuxt_context.$S.data[h.dataKey]
                                        .users_hash,
                                      "users/".concat(t.id),
                                      t,
                                    );
                                  }),
                                  (t.next = 4));
                                break;
                              case 3:
                                ((t.prev = 3), (l = t.catch(1)), e(l));
                              case 4:
                              case "end":
                                return t.stop();
                            }
                        },
                        t,
                        this,
                        [[1, 3]],
                      );
                    }),
                  )),
                  function (e) {
                    return t.apply(this, arguments);
                  }),
              },
              {
                key: "order",
                value: function () {
                  return _.find(
                    this.nuxt_context.$S.data[this.dataKey].sort_bys,
                    { is_active: !0 },
                  ).slug;
                },
              },
            ],
          );
          var t, n, r;
        })(),
        Y = K,
        z = r(42),
        J = {
          middleware: [],
          head: {
            title:
              "Watch Free Anime Hentai Video Streams Online in 720p, 1080p HD",
          },
          components: {
            "htv-footer": r(301).default,
            "nav-drawer": r(302).default,
            toolbar: r(303).default,
            snackbar: r(304).default,
            "contact-us-dialog": r(305).default,
            "general-confirmation-dialog": r(306).default,
          },
          beforeCreate: function () {
            globalThis.default_color = "#f3c669";
            var t = globalThis.default_color;
            (this.$S.user_setting &&
              this.$S.user_setting.primary_color &&
              (t = this.$S.user_setting.primary_color),
              (this.$vuetify.theme = { primary: t, accent: t }));
          },
          created: function () {},
          data: function () {
            return {};
          },
          beforeMount: function () {
            var t = this;
            return Object(o.a)(
              regeneratorRuntime.mark(function e() {
                var n, r;
                return regeneratorRuntime.wrap(function (e) {
                  for (;;)
                    switch ((e.prev = e.next)) {
                      case 0:
                        ((globalThis.Search = z.a),
                          (globalThis.CommentsSystem = Y),
                          window.parent != window.top &&
                            (window.top.location.href =
                              window.self.location.href),
                          (t.site_loaded_at = new Date().getTime()),
                          (t._updateBrowserDimensions = _.debounce(
                            t.updateBrowserDimensions,
                            1e3,
                            { trailing: !0 },
                          )),
                          (t._doSearch = _.debounce(
                            function () {
                              return z.a.doSearch(t.$S);
                            },
                            100,
                            { leading: !1, trailing: !0 },
                          )),
                          (n = {
                            "/signout": function () {
                              t.$App.logout({});
                            },
                            "/sign-in": function () {
                              window.location.href = "/sign-in?cache=".concat(
                                new Date().getTime(),
                              );
                            },
                            "/create-account": function () {
                              window.location.href =
                                "/create-account?cache=".concat(
                                  new Date().getTime(),
                                );
                            },
                            "/forgot-password": function () {
                              window.location.href =
                                "/forgot-password?cache=".concat(
                                  new Date().getTime(),
                                );
                            },
                            "/feedback": function () {
                              t.$EVT.$emit(
                                t.$EVT.OPEN,
                                t.$EVT.CONTACT_US_DIALOG,
                              );
                            },
                            "/report-video": function () {
                              t.$EVT.$emit(
                                t.$EVT.OPEN,
                                t.$EVT.CONTACT_US_DIALOG,
                                t.$EVT.CONTACT_US_ALLOW_VIDEO_REPORT,
                              );
                            },
                          }),
                          t.$EVT.$on(t.$EVT.GOTO, function (e) {
                            (t.$EVT.$emit(t.$EVT.OPEN, null),
                              n[e] && "function" == typeof n[e]
                                ? n[e]()
                                : t.$router.push(e));
                          }),
                          t.$EVT.$on(t.$EVT.NEW_TAB, function (t) {
                            window.open(t);
                          }),
                          t.$EVT.$on(
                            t.$EVT.SAVE_USER_SEARCH_STATE,
                            function () {
                              z.a.saveUserSearchState(t.$S);
                            },
                          ),
                          t.$S.$on("SAVE", t.$S.save),
                          (r = navigator.userAgent),
                          (t.$App.user_agent = r),
                          (t.$App.is_bot =
                            /bot|googlebot|crawler|spider|robot|crawling/i.test(
                              r,
                            )),
                          (t.$App.is_mac = /Mac/i.test(navigator.platform)),
                          (t.$App.is_ios = /(iPhone|iPad|iPod)/.test(r)),
                          (t.$App.is_android = /Android/.test(r)),
                          (t.$App.is_other =
                            !t.$App.is_mac &&
                            !t.$App.is_ios &&
                            !t.$App.is_android),
                          (t.$App.is_safari =
                            /^((?!chrome|android).)*safari/i.test(r)),
                          (t.$App.is_edge = /Edge/.test(r)),
                          (t.$App.is_local_storage_available =
                            t.isStorageAvailable("localStorage")),
                          t.updateBrowserDimensions(),
                          t.$EVT.$on(t.$EVT.DO_SEARCH, function () {
                            ((t.$S.search.is_using_preferences =
                              "search" == $nuxt.$route.name),
                              t._doSearch());
                          }),
                          t.$EVT.$on(t.$EVT.DO_NEW_SEARCH, function () {
                            ((t.$S.search.page = 1),
                              (t.$S.search.offset = 0),
                              (t.$S.search.is_using_preferences =
                                "search" == $nuxt.$route.name),
                              t._doSearch());
                          }));
                      case 1:
                      case "end":
                        return e.stop();
                    }
                }, e);
              }),
            )();
          },
          mounted: function () {
            var t = this;
            (this.initializeScrolling(),
              window.addEventListener("message", this.$S.onWindowMessage, !1),
              document.documentElement.style.setProperty(
                "--primary",
                this.$vuetify.theme.primary,
              ),
              window.addEventListener("resize", this._updateBrowserDimensions),
              this.initializeSearch());
            try {
              new SimpleBar(this.$refs.site_description, {
                autoHide: !1,
                classNames: { horizontal: "sd-horizontal-bar" },
              });
            } catch (t) {
              e(t);
            }
            (this.$isSPAStale(),
              window.stime
                ? this.finalizer()
                : (this.stime_poll = setInterval(function () {
                    (Emit("e"),
                      window.stime &&
                        (t.finalizer(),
                        clearInterval(t.stime_poll),
                        (t.stime_poll = null)));
                  }, 100)),
              this.getAndSetCSRFToken());
          },
          beforeDestroy: function () {},
          methods: {
            finalizer: function () {
              ((this.$S.is_mounted = !0),
                this.$S.$emit("APP_FULLY_MOUNTED"),
                z.a.init(this));
            },
            initializeScrolling: function () {
              (this.$App.is_ios,
                window.addEventListener("scroll", this.onScroll));
            },
            onScroll: function (t) {
              ((window.last_known_scroll_position = window.scrollY),
                this.requestTick());
            },
            requestTick: function () {
              (window.ticking || requestAnimationFrame(this.updateScrollY),
                (window.ticking = !0));
            },
            updateScrollY: function () {
              ((this.$S.scrollY = window.last_known_scroll_position),
                (window.ticking = !1));
            },
            initializeSearch: function () {
              ((this.$S.search.tags = z.a.tags),
                (this.$S.search.brands = z.a.brands),
                (this.$S.search.blacklisted_tags = z.a.blacklisted_tags),
                z.a.restoreUserSearchState(this.$S));
            },
            updateBrowserDimensions: function () {
              ((this.$S.browser_width = Math.max(
                document.body.scrollWidth,
                document.documentElement.scrollWidth,
                document.body.offsetWidth,
                document.documentElement.offsetWidth,
                document.documentElement.clientWidth,
                320,
              )),
                (this.$S.browser_height = window.innerHeight));
            },
            onResize: function () {
              this._updateBrowserDimensions();
            },
            onRouteChanged: function () {
              (this.$EVT.$emit(this.$EVT.ROUTE_CHANGED),
                this.$S.user &&
                  (this.$EVT.$emit(this.$EVT.LOGGED_IN),
                  (this.$S.scrollY = 1),
                  (this.$S.scrollY = 0)),
                this.getAndSetCSRFToken(),
                this.$doCacheBust());
            },
            isStorageAvailable: function (t) {
              if ("undefined" == typeof window) return !1;
              var e = null;
              try {
                e = window[t];
              } catch (t) {
                return !1;
              }
              if (void 0 === e) return !1;
              try {
                var n = "__storage_test__";
                return (e.setItem(n, n), e.removeItem(n), !0);
              } catch (t) {
                return !1;
              }
            },
            getAndSetCSRFToken: function () {
              var t = this;
              if (S.user) {
                var e = Math.floor(Date.now() / 1e3);
                (this.$S.csrf_token_last_fetched_time_unix &&
                  e - this.$S.csrf_token_last_fetched_time_unix < 36e3) ||
                  this.$get("/csrf_token")
                    .then(function (e) {
                      e &&
                        e.data &&
                        e.data.csrf_token &&
                        ((t.$S.csrf_token = e.data.csrf_token),
                        (t.$S.csrf_token_last_fetched_time_unix = Math.floor(
                          Date.now() / 1e3,
                        )));
                    })
                    .catch(function (t) {});
              }
            },
          },
          watch: { $route: "onRouteChanged" },
          computed: {},
        },
        X = Object(N.a)(
          J,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              "v-app",
              {
                class: "app-".concat(t.$nuxt.$route.name, "-page"),
                attrs: { dark: "", toolbar: "", footer: "" },
              },
              [
                e("div", { attrs: { id: "top" } }),
                t._v(" "),
                e("nav-drawer"),
                t._v(" "),
                e("toolbar"),
                t._v(" "),
                e(
                  "v-content",
                  [
                    e(
                      "v-container",
                      { staticClass: "pa-0", attrs: { fluid: "" } },
                      [e("nuxt")],
                      1,
                    ),
                  ],
                  1,
                ),
                t._v(" "),
                e("div", { staticClass: "site-description" }, [
                  e("div", { staticClass: "sd-fader" }),
                  t._v(" "),
                  e("div", { staticClass: "sd-image" }),
                  t._v(" "),
                  e(
                    "div",
                    { ref: "site_description", staticClass: "sd-content" },
                    [
                      e("h2", { staticClass: "mb-3" }, [
                        t._v("Watch Hentai online at hanime"),
                        e("span", { staticClass: "primary-color" }, [
                          t._v("."),
                        ]),
                        t._v("tv"),
                      ]),
                      t._v(" "),
                      e("p", [
                        t._v("In hanime"),
                        e("span", { staticClass: "primary-color" }, [
                          t._v("."),
                        ]),
                        t._v(
                          "tv you will find a hentai haven for the latest uncensored Hentai.\n      We offer the best hentai collection in the highest possible quality at 1080p from\n      Blu-Ray rips.  Many videos are licensed direct downloads from the original animators,\n      producers, or publishing source company in Japan.",
                        ),
                      ]),
                      t._v(" "),
                      e("p", [
                        t._v(
                          "If you're looking for the latest Hentai videos of various genres, hanime",
                        ),
                        e("span", { staticClass: "primary-color" }, [
                          t._v("."),
                        ]),
                        t._v(
                          "tv is\n      exactly what you need.  Our website is an international hub of Hentai animation.\n      Here you will find a great collection of uncensored Hentai videos as well as links to sex\n      games and porn.  Browse our catalog to find the most exciting and hot Hentai anime.\n      We have both old-school videos for real admirers and the latest Hentai episodes for\n      those who would like to stay up to date. We stream thousands of Hentai videos in HD\n      quality that you can watch on your PC, tablet, and mobile phone.",
                        ),
                      ]),
                      t._v(" "),
                      e("p", [
                        t._v("With hanime"),
                        e("span", { staticClass: "primary-color" }, [
                          t._v("."),
                        ]),
                        t._v(
                          "tv,\n      you can watch the newest Hentai series and follow your favorite characters.  Whether\n      you like a raw fap material or a well-developed plot, we have got you covered.  Here,\n      you can find Hentai that focuses on the physical aspect of love as well as romance.\n      We strive to provide the best experience to all our clients, that is why you can always\n      click the “subbed” tag to follow the plot if you do not know Japanese.",
                        ),
                      ]),
                      t._v(" "),
                      e("h2", { staticClass: "mb-3 mt-4" }, [
                        t._v(
                          "I want to watch free uncensored anime hentai videos online in\n      720p 1080p HD quality",
                        ),
                      ]),
                      t._v(" "),
                      e(
                        "p",
                        [
                          t._v("Connected to many leaks, hanime"),
                          e("span", { staticClass: "primary-color" }, [
                            t._v("."),
                          ]),
                          t._v(
                            "tv is where you can watch hentai with just one click.\n      Including hentai in and up to 2022, where is the latest hentai are archived and\n      curated here.  Here is the place where you can find the best hentai online 24/7.\n      Enjoy hentai movies, hentai clips, and also hentai pictures images for free!\n      This site is the best place for ecchi since hentai haven, and includes many hentai\n      categories like:\n      ",
                          ),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/incest" } },
                            [t._v("Incest hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/milf" } },
                            [t._v("Milf hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/anal" } },
                            [t._v("Anal Hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/creampie" } },
                            [t._v("Creampie Hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/futanari" } },
                            [t._v("Futanari Hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/school%20girl" } },
                            [t._v("School Girls Hentai")],
                          ),
                          t._v(",\n      "),
                          e(
                            "router-link",
                            { attrs: { to: "/browse/tags/yuri" } },
                            [t._v("Yuri Hentai")],
                          ),
                          t._v(",\n      and much more!"),
                        ],
                        1,
                      ),
                      t._v(" "),
                      e("h2", { staticClass: "mb-3 mt-4" }, [
                        t._v("Join our hentai hanime"),
                        e("span", { staticClass: "primary-color" }, [
                          t._v("."),
                        ]),
                        t._v("tv fans community Discord"),
                      ]),
                      t._v(" "),
                      e("p", [
                        t._v(
                          "Our fans' community Discord is 145,000+ members strong and growing!  Join one of the\n      largest - if not, the largest hentai fans community on the internet.  Socialize with\n      like-minded friends, upload pictures images and video clips, share your favorite music\n      or DJ and livestream the games you play!",
                        ),
                      ]),
                      t._v(" "),
                      e("p", { staticClass: "sd-space" }, [
                        t._v(
                          "What is Hentai?  Hentai (変態 or へんたい). Hentai or seijin-anime is a Japanese word that,\n      in the West, is used when referring to sexually explicit or pornographic comics and animation,\n      particularly those of Japanese origin such as anime and manga.",
                        ),
                      ]),
                    ],
                  ),
                ]),
                t._v(" "),
                e("htv-footer"),
                t._v(" "),
                e(
                  "v-fab-transition",
                  [
                    e(
                      "v-btn",
                      {
                        directives: [
                          {
                            name: "show",
                            rawName: "v-show",
                            value: t.$S.scrollY > 0,
                            expression: "$S.scrollY > 0",
                          },
                        ],
                        staticClass: "search-fab elevation-3",
                        attrs: {
                          fixed: "",
                          dark: "",
                          fab: "",
                          bottom: "",
                          right: "",
                          color: "primary",
                        },
                        on: { click: t.$scrollTop },
                      },
                      [e("v-icon", [t._v("mdi-chevron-up")])],
                      1,
                    ),
                  ],
                  1,
                ),
                t._v(" "),
                e("contact-us-dialog"),
                t._v(" "),
                e("general-confirmation-dialog"),
                t._v(" "),
                e("snackbar"),
              ],
              1,
            );
          },
          [],
          !1,
          null,
          null,
          null,
        ).exports;
      function Q(t, e) {
        var n =
          ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
          t["@@iterator"];
        if (!n) {
          if (
            Array.isArray(t) ||
            (n = (function (t, a) {
              if (t) {
                if ("string" == typeof t) return Z(t, a);
                var e = {}.toString.call(t).slice(8, -1);
                return (
                  "Object" === e && t.constructor && (e = t.constructor.name),
                  "Map" === e || "Set" === e
                    ? Array.from(t)
                    : "Arguments" === e ||
                        /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                      ? Z(t, a)
                      : void 0
                );
              }
            })(t)) ||
            (e && t && "number" == typeof t.length)
          ) {
            n && (t = n);
            var r = 0,
              o = function () {};
            return {
              s: o,
              n: function () {
                return r >= t.length
                  ? { done: !0 }
                  : { done: !1, value: t[r++] };
              },
              e: function (t) {
                throw t;
              },
              f: o,
            };
          }
          throw new TypeError(
            "Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.",
          );
        }
        var c,
          a = !0,
          u = !1;
        return {
          s: function () {
            n = n.call(t);
          },
          n: function () {
            var t = n.next();
            return ((a = t.done), t);
          },
          e: function (t) {
            ((u = !0), (c = t));
          },
          f: function () {
            try {
              a || null == n.return || n.return();
            } finally {
              if (u) throw c;
            }
          },
        };
      }
      function Z(t, a) {
        (null == a || a > t.length) && (a = t.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
        return n;
      }
      var tt = { _default: Object(O.s)(X) },
        et = {
          render: function (t, e) {
            var n = t("NuxtLoading", { ref: "loading" }),
              r = t(this.layout || "nuxt"),
              o = t(
                "div",
                { domProps: { id: "__layout" }, key: this.layoutName },
                [r],
              ),
              c = t(
                "transition",
                {
                  props: { name: "layout", mode: "out-in" },
                  on: {
                    beforeEnter: function (t) {
                      window.$nuxt.$nextTick(function () {
                        window.$nuxt.$emit("triggerScroll");
                      });
                    },
                  },
                },
                [o],
              );
            return t("div", { domProps: { id: "__nuxt" } }, [n, c]);
          },
          data: function () {
            return {
              isOnline: !0,
              layout: null,
              layoutName: "",
              nbFetching: 0,
            };
          },
          beforeCreate: function () {
            d.a.util.defineReactive(this, "nuxt", this.$options.nuxt);
          },
          created: function () {
            ((this.$root.$options.$nuxt = this),
              (window.$nuxt = this),
              this.refreshOnlineStatus(),
              window.addEventListener("online", this.refreshOnlineStatus),
              window.addEventListener("offline", this.refreshOnlineStatus),
              (this.error = this.nuxt.error),
              (this.context = this.$options.context));
          },
          mounted: function () {
            var t = this;
            return Object(o.a)(
              regeneratorRuntime.mark(function e() {
                return regeneratorRuntime.wrap(function (e) {
                  for (;;)
                    switch ((e.prev = e.next)) {
                      case 0:
                        t.$loading = t.$refs.loading;
                      case 1:
                      case "end":
                        return e.stop();
                    }
                }, e);
              }),
            )();
          },
          watch: { "nuxt.err": "errorChanged" },
          computed: {
            isOffline: function () {
              return !this.isOnline;
            },
            isFetching: function () {
              return this.nbFetching > 0;
            },
          },
          methods: {
            refreshOnlineStatus: function () {
              void 0 === window.navigator.onLine
                ? (this.isOnline = !0)
                : (this.isOnline = window.navigator.onLine);
            },
            refresh: function () {
              var t = this;
              return Object(o.a)(
                regeneratorRuntime.mark(function e() {
                  var n, r, c;
                  return regeneratorRuntime.wrap(
                    function (e) {
                      for (;;)
                        switch ((e.prev = e.next)) {
                          case 0:
                            if ((n = Object(O.h)(t.$route)).length) {
                              e.next = 1;
                              break;
                            }
                            return e.abrupt("return");
                          case 1:
                            return (
                              t.$loading.start(),
                              (r = n.map(
                                (function () {
                                  var e = Object(o.a)(
                                    regeneratorRuntime.mark(function e(n) {
                                      var p, r, o, component;
                                      return regeneratorRuntime.wrap(function (
                                        e,
                                      ) {
                                        for (;;)
                                          switch ((e.prev = e.next)) {
                                            case 0:
                                              return (
                                                (p = []),
                                                n.$options.fetch &&
                                                  n.$options.fetch.length &&
                                                  p.push(
                                                    Object(O.q)(
                                                      n.$options.fetch,
                                                      t.context,
                                                    ),
                                                  ),
                                                n.$options.asyncData &&
                                                  p.push(
                                                    Object(O.q)(
                                                      n.$options.asyncData,
                                                      t.context,
                                                    ).then(function (t) {
                                                      for (var e in t)
                                                        d.a.set(
                                                          n.$data,
                                                          e,
                                                          t[e],
                                                        );
                                                    }),
                                                  ),
                                                (e.next = 1),
                                                Promise.all(p)
                                              );
                                            case 1:
                                              ((p = []),
                                                n.$fetch && p.push(n.$fetch()),
                                                (r = Q(
                                                  Object(O.e)(
                                                    n.$vnode.componentInstance,
                                                  ),
                                                )));
                                              try {
                                                for (r.s(); !(o = r.n()).done; )
                                                  ((component = o.value),
                                                    p.push(component.$fetch()));
                                              } catch (t) {
                                                r.e(t);
                                              } finally {
                                                r.f();
                                              }
                                              return e.abrupt(
                                                "return",
                                                Promise.all(p),
                                              );
                                            case 2:
                                            case "end":
                                              return e.stop();
                                          }
                                      }, e);
                                    }),
                                  );
                                  return function (t) {
                                    return e.apply(this, arguments);
                                  };
                                })(),
                              )),
                              (e.prev = 2),
                              (e.next = 3),
                              Promise.all(r)
                            );
                          case 3:
                            e.next = 5;
                            break;
                          case 4:
                            ((e.prev = 4),
                              (c = e.catch(2)),
                              t.$loading.fail(c),
                              Object(O.k)(c),
                              t.error(c));
                          case 5:
                            t.$loading.finish();
                          case 6:
                          case "end":
                            return e.stop();
                        }
                    },
                    e,
                    null,
                    [[2, 4]],
                  );
                }),
              )();
            },
            errorChanged: function () {
              if (this.nuxt.err) {
                this.$loading &&
                  (this.$loading.fail && this.$loading.fail(this.nuxt.err),
                  this.$loading.finish && this.$loading.finish());
                var t = (I.options || I).layout;
                ("function" == typeof t && (t = t(this.context)),
                  (this.nuxt.errPageReady = !0),
                  this.setLayout(t));
              }
            },
            setLayout: function (t) {
              return (
                (t && tt["_" + t]) || (t = "default"),
                (this.layoutName = t),
                (this.layout = tt["_" + t]),
                this.layout
              );
            },
            loadLayout: function (t) {
              return (
                (t && tt["_" + t]) || (t = "default"),
                Promise.resolve(tt["_" + t])
              );
            },
          },
          components: { NuxtLoading: M },
        },
        nt = r(220),
        it = r.n(nt),
        at = r(221),
        ot = r.n(at);
      ((globalThis.l = function () {}),
        (globalThis.e = console.error.bind(console)));
      var st = function (t, e) {
          var n;
          "undefined" == typeof window
            ? (((n = globalThis).is_server = !0), (n.is_client = !1))
            : (((n = window).is_server = !1), (n.is_client = !0));
          var o = !1,
            c = !0;
          ((n.domain = "hanime.tv"),
            (n.cookie_domain = "hanime.tv"),
            (n.is_prod = c),
            (n.is_dev = o),
            (n.transparent =
              "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"),
            e("EVT", (0, r(285).newEvents)(t)));
          var l = (0, r(286).newState)(t);
          ((l.is_dev = o),
            (l.is_prod = c),
            "undefined" != typeof window &&
              window.__NUXT__ &&
              window.__NUXT__.state &&
              (Object.keys(window.__NUXT__.state).forEach(function (t) {
                l.$set(l, t, window.__NUXT__.state[t]);
              }),
              (window.ssignature = ""),
              (window.stime = 0)),
            d.a.use(it.a, { theme: { disable: !0 } }),
            d.a.use(ot.a, { lazyComponent: !0 }),
            d.a.filter("truncate", function (t, e) {
              return t && t.length > e ? t.substring(0, e) + "…" : t;
            }));
          var _ = r(291),
            h = (0, r(292).newAppSingleton)(t),
            f = r(295),
            v = f.doCacheBust,
            m = f.isSPAStale;
          (e("S", l),
            e("App", h),
            e("scrollTop", function () {
              $nuxt.$vuetify.goTo("#top", {
                duration: 300,
                offset: 0,
                easing: "easeInOutCubic",
              });
            }),
            e("PlaylistManagement", _),
            e("doCacheBust", function (e) {
              return v(t, e);
            }),
            e("isSPAStale", function (e) {
              return m(t, e);
            }),
            (window.S = l),
            (window.App = h),
            (window.ctx = t));
        },
        ct = r(158),
        ut = r.n(ct);
      function lt(t, e) {
        var n = Object.keys(t);
        if (Object.getOwnPropertySymbols) {
          var r = Object.getOwnPropertySymbols(t);
          (e &&
            (r = r.filter(function (e) {
              return Object.getOwnPropertyDescriptor(t, e).enumerable;
            })),
            n.push.apply(n, r));
        }
        return n;
      }
      function _t(t) {
        for (var e = 1; e < arguments.length; e++) {
          var n = null != arguments[e] ? arguments[e] : {};
          e % 2
            ? lt(Object(n), !0).forEach(function (e) {
                Object(c.a)(t, e, n[e]);
              })
            : Object.getOwnPropertyDescriptors
              ? Object.defineProperties(t, Object.getOwnPropertyDescriptors(n))
              : lt(Object(n)).forEach(function (e) {
                  Object.defineProperty(
                    t,
                    e,
                    Object.getOwnPropertyDescriptor(n, e),
                  );
                });
        }
        return t;
      }
      (d.a.component(v.a.name, v.a),
        d.a.component(
          x.a.name,
          _t(
            _t({}, x.a),
            {},
            {
              render: function (t, e) {
                return (
                  x.a._warned ||
                    ((x.a._warned = !0),
                    console.warn(
                      "<no-ssr> has been deprecated and will be removed in Nuxt 3, please use <client-only> instead",
                    )),
                  x.a.render(t, e)
                );
              },
            },
          ),
        ),
        d.a.component(j.name, j),
        d.a.component("NChild", j),
        d.a.component(V.name, V),
        Object.defineProperty(d.a.prototype, "$nuxt", {
          get: function () {
            var t = this.$root ? this.$root.$options.$nuxt : null;
            return t || "undefined" == typeof window ? t : window.$nuxt;
          },
          configurable: !0,
        }),
        d.a.use(h.a, {
          keyName: "head",
          attribute: "data-n-head",
          ssrAttribute: "data-n-head-ssr",
          tagIDKeyName: "hid",
        }));
      var ht = {
        name: "page",
        mode: "out-in",
        appear: !1,
        appearClass: "appear",
        appearActiveClass: "appear-active",
        appearToClass: "appear-to",
      };
      function pt(t) {
        return ft.apply(this, arguments);
      }
      function ft() {
        return (
          (ft = Object(o.a)(
            regeneratorRuntime.mark(function t(e) {
              var n,
                r,
                c,
                l,
                _,
                path,
                h,
                f = arguments;
              return regeneratorRuntime.wrap(function (t) {
                for (;;)
                  switch ((t.prev = t.next)) {
                    case 0:
                      return (
                        (h = function (t, e) {
                          if (!t)
                            throw new Error(
                              "inject(key, value) has no key provided",
                            );
                          if (void 0 === e)
                            throw new Error(
                              "inject('".concat(
                                t,
                                "', value) has no value provided",
                              ),
                            );
                          ((c[(t = "$" + t)] = e),
                            c.context[t] || (c.context[t] = e));
                          var n = "__nuxt_" + t + "_installed__";
                          d.a[n] ||
                            ((d.a[n] = !0),
                            d.a.use(function () {
                              Object.prototype.hasOwnProperty.call(
                                d.a.prototype,
                                t,
                              ) ||
                                Object.defineProperty(d.a.prototype, t, {
                                  get: function () {
                                    return this.$root.$options[t];
                                  },
                                });
                            }));
                        }),
                        (n = f.length > 1 && void 0 !== f[1] ? f[1] : {}),
                        (t.next = 1),
                        A(0, n)
                      );
                    case 1:
                      return (
                        (r = t.sent),
                        (c = _t(
                          {
                            head: {
                              htmlAttrs: { lang: "en" },
                              meta: [
                                { charset: "utf-8" },
                                {
                                  name: "viewport",
                                  content:
                                    "width=device-width, initial-scale=1.0",
                                },
                                { name: "theme-color", content: "#f3c669" },
                                {
                                  hid: "description",
                                  name: "description",
                                  content:
                                    "Watch free hentai video online from your mobile phone, tablet, desktop, in 720p and 1080p.  Regular update with the latest HD releases: uncensored, subbed, 720p, 1080p, and more!",
                                },
                              ],
                              style: [
                                {
                                  cssText:
                                    "body,html{background:#303030;margin:0;padding:0}",
                                  type: "text/css",
                                },
                              ],
                              link: [
                                {
                                  ref: "preconnect",
                                  href: "https://hanime-cdn.com",
                                },
                                {
                                  rel: "preconnect",
                                  href: "https://cdn.jsdelivr.net",
                                },
                                {
                                  rel: "preconnect",
                                  href: "https://cached.freeanimehentai.net",
                                },
                                {
                                  rel: "preconnect",
                                  href: "https://h.freeanimehentai.net",
                                },
                                {
                                  rel: "apple-touch-icon-precomposed",
                                  href: "/apple-touch-icon-precomposed.png",
                                },
                                {
                                  rel: "apple-touch-icon",
                                  href: "/apple-touch-icon.png",
                                },
                                { rel: "icon", href: "/favicon.png" },
                                { rel: "shortcut icon", href: "/favicon.ico" },
                                {
                                  rel: "preload",
                                  as: "style",
                                  href: "https://cdn.jsdelivr.net/npm/vuetify@1.0.19/dist/vuetify.min.css",
                                  onload: "this.rel='stylesheet'",
                                },
                                {
                                  rel: "preload",
                                  as: "style",
                                  href: "https://cdn.jsdelivr.net/npm/@mdi/font@2.6.95/css/materialdesignicons.min.css",
                                  onload: "this.rel='stylesheet'",
                                },
                                {
                                  rel: "preload",
                                  as: "style",
                                  href: "/assets/css/app.CPFMR-LU.min.css",
                                  onload: "this.rel='stylesheet'",
                                },
                              ],
                              script: [
                                {
                                  src: "https://cdn.jsdelivr.net/npm/vue@2.7.16/dist/vue.runtime.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/vuetify@1.0.19/dist/vuetify.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/vue-lazyload@1.2.6/vue-lazyload.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/vue-router@3.6.5/dist/vue-router.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/js-cookie@3.0.5/dist/js.cookie.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://hanime-cdn.com/js/vendor.0130da3e01eaf5c7d570b6ed1becb5f4.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/simplebar@6.3.2/dist/simplebar.min.js",
                                  defer: !0,
                                },
                                {
                                  src: "https://cdn.jsdelivr.net/npm/minisearch@7.2.0/dist/umd/index.min.js",
                                  defer: !0,
                                },
                              ],
                            },
                            router: r,
                            nuxt: {
                              defaultTransition: ht,
                              transitions: [ht],
                              setTransitions: function (t) {
                                return (
                                  Array.isArray(t) || (t = [t]),
                                  (t = t.map(function (t) {
                                    return (t = t
                                      ? "string" == typeof t
                                        ? Object.assign({}, ht, { name: t })
                                        : Object.assign({}, ht, t)
                                      : ht);
                                  })),
                                  (this.$options.nuxt.transitions = t),
                                  t
                                );
                              },
                              err: null,
                              errPageReady: !1,
                              dateErr: null,
                              error: function (t) {
                                ((t = t || null),
                                  (c.context._errored = Boolean(t)),
                                  (t = t ? Object(O.p)(t) : null));
                                var n = c.nuxt;
                                return (
                                  this && (n = this.nuxt || this.$options.nuxt),
                                  (n.dateErr = Date.now()),
                                  (n.err = t),
                                  (n.errPageReady = !1),
                                  e && (e.nuxt.error = t),
                                  t
                                );
                              },
                            },
                          },
                          et,
                        )),
                        (l = e
                          ? e.next
                          : function (t) {
                              return c.router.push(t);
                            }),
                        e
                          ? (_ = r.resolve(e.url).route)
                          : ((path = Object(O.f)(
                              r.options.base,
                              r.options.mode,
                            )),
                            (_ = r.resolve(path).route)),
                        (t.next = 2),
                        Object(O.t)(c, {
                          route: _,
                          next: l,
                          error: c.nuxt.error.bind(c),
                          payload: e ? e.payload : void 0,
                          req: e ? e.req : void 0,
                          res: e ? e.res : void 0,
                          beforeRenderFns: e ? e.beforeRenderFns : void 0,
                          beforeSerializeFns: e ? e.beforeSerializeFns : void 0,
                          ssrContext: e,
                        })
                      );
                    case 2:
                      return (h("config", n), (t.next = 3), st(c.context, h));
                    case 3:
                      if ("function" != typeof ut.a) {
                        t.next = 4;
                        break;
                      }
                      return ((t.next = 4), ut()(c.context, h));
                    case 4:
                      return (
                        (t.next = 5),
                        new Promise(function (t, e) {
                          if (
                            !r.resolve(c.context.route.fullPath).route.matched
                              .length
                          )
                            return t();
                          r.replace(c.context.route.fullPath, t, function (n) {
                            if (!n._isRouter) return e(n);
                            if (2 !== n.type) return t();
                            var l = r.afterEach(
                              (function () {
                                var e = Object(o.a)(
                                  regeneratorRuntime.mark(function e(n, r) {
                                    return regeneratorRuntime.wrap(function (
                                      e,
                                    ) {
                                      for (;;)
                                        switch ((e.prev = e.next)) {
                                          case 0:
                                            return (
                                              (e.next = 1),
                                              Object(O.j)(n)
                                            );
                                          case 1:
                                            ((c.context.route = e.sent),
                                              (c.context.params =
                                                n.params || {}),
                                              (c.context.query = n.query || {}),
                                              l(),
                                              t());
                                          case 2:
                                          case "end":
                                            return e.stop();
                                        }
                                    }, e);
                                  }),
                                );
                                return function (t, n) {
                                  return e.apply(this, arguments);
                                };
                              })(),
                            );
                          });
                        })
                      );
                    case 5:
                      return t.abrupt("return", { app: c, router: r });
                    case 6:
                    case "end":
                      return t.stop();
                  }
              }, t);
            }),
          )),
          ft.apply(this, arguments)
        );
      }
    },
  },
  [[223, 51, 2, 52]],
]);
