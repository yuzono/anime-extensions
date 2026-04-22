(window.webpackJsonp = window.webpackJsonp || []).push([
  [35],
  {
    308: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = {
          props: [],
          data: function () {
            return {};
          },
          beforeMount: function () {},
          mounted: function () {},
          methods: {
            onShow: function () {
              this.$emit("show");
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
              "div",
              { staticClass: "lazy" },
              [
                e(
                  "transition",
                  { attrs: { name: "fade" } },
                  [
                    e(
                      "lazy-component",
                      { on: { show: t.onShow } },
                      [t._t("default")],
                      2,
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
    309: function (t, e, n) {
      "use strict";
      n.r(e);
      n(45);
      var r = {
          components: {},
          props: ["placement", "data", "isCloseable"],
          beforeCreate: function () {},
          beforeMount: function () {
            ((this.form_factor = "mobile"),
              this.$S.browser_width >= 960 && (this.form_factor = "desktop"),
              this.data &&
                this.data[this.placement] &&
                (this.unit_data = this.data[this.placement][this.form_factor]));
          },
          data: function () {
            return { unit_data: null, form_factor: "mobile", is_closed: !1 };
          },
          mounted: function () {},
          methods: {
            close: function () {
              this.is_closed = !0;
            },
          },
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this,
              e = t._self._c;
            return !t.$App.is_bot &&
              t.$S.is_mounted &&
              t.unit_data &&
              !t.is_closed
              ? e(
                  "div",
                  {
                    class: "htvad "
                      .concat(t.form_factor, " ")
                      .concat(t.$S.scrollY > 0 ? "active" : "", " ")
                      .concat(
                        "smart" == t.unit_data.placement_x
                          ? "smart_banner"
                          : "",
                      ),
                    style: {
                      width: "".concat(t.unit_data.width, "px"),
                      display: "block",
                    },
                  },
                  [
                    e("div", { staticClass: "unit__text" }, [
                      t._v("Advertisement"),
                    ]),
                    t._v(" "),
                    e(
                      "v-btn",
                      {
                        staticClass:
                          "unit__close flex justify-center align-center",
                        attrs: { outline: "" },
                        on: {
                          click: function (e) {
                            return t.close();
                          },
                        },
                      },
                      [
                        e("span", { staticClass: "pr-2" }, [t._v("Close Ad")]),
                        t._v(" "),
                        e("v-icon", [t._v("mdi-close")]),
                      ],
                      1,
                    ),
                    t._v(" "),
                    e("div", { staticClass: "unit__container flex" }, [
                      t._m(0),
                      t._v(" "),
                      "banner_image" == t.unit_data.ad_type
                        ? e(
                            "a",
                            {
                              staticClass: "unit__link unit__content",
                              attrs: {
                                href: t.unit_data.click_url,
                                rel: "noopener",
                                target: "_blank",
                              },
                            },
                            [
                              e("img", {
                                attrs: { src: t.unit_data.image_url },
                              }),
                            ],
                          )
                        : "ifr" == t.unit_data.ad_type
                          ? e("iframe", {
                              staticClass: "unit__frame unit__content",
                              attrs: {
                                src: t.unit_data.iframe_url,
                                width: t.unit_data.width,
                                height: t.unit_data.height,
                                scrolling: "no",
                                marginWidth: "0",
                                marginHeight: "0",
                                frameBorder: "0",
                                allowTransparency: "true",
                              },
                            })
                          : t._e(),
                    ]),
                  ],
                  1,
                )
              : t._e();
          },
          [
            function () {
              var t = this._self._c;
              return t(
                "div",
                {
                  staticClass:
                    "unit__block flex row justify-center align-center",
                },
                [
                  t("span", [
                    this._v(
                      "We are supported primarily by advertisements.\n      Please whitelist us so we can continue to build new\n      features. You'll thank us later. :)",
                    ),
                  ]),
                ],
              );
            },
          ],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    311: function (t, n, r) {
      "use strict";
      r.r(n);
      var o = r(5),
        c =
          (r(27),
          r(45),
          r(46),
          r(19),
          r(61),
          {
            props: ["data", "isDeleteable"],
            beforeCreate: function () {},
            data: function () {
              return { is_music_playing: !1, is_mounted: !1 };
            },
            mounted: function () {
              this.is_mounted = !0;
            },
            methods: {
              toggleAudioPlay: function (t) {
                this.is_music_playing
                  ? (t.target.pause(), (this.is_music_playing = !1))
                  : (t.target.play(), (this.is_music_playing = !0));
              },
              confirmDestroy: function () {
                var t = this.data.url.split("/"),
                  e = t[t.length - 1];
                this.$EVT.$emit(
                  this.$EVT.ACTIVATE_GENERAL_CONFIRMATION_DIALOG,
                  {
                    title: "Delete this image?",
                    body: 'Are you sure you want to delete this image?<br /><code style="overflow: hidden;">'.concat(
                      e,
                      "</code>",
                    ),
                    confirm_button_text: "Delete",
                    confirmation_callback: this.destroy,
                    is_mini_close_button_visible: !0,
                    is_cancel_button_visible: !0,
                    is_persistent: !1,
                  },
                );
              },
              destroy: function () {
                var t = this;
                return Object(o.a)(
                  regeneratorRuntime.mark(function n() {
                    var r, o, data, c, l;
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
                                (r = t.data.id),
                                (n.prev = 2),
                                (n.next = 3),
                                t.$del(
                                  ""
                                    .concat(
                                      t.$getApiBaseUrl(),
                                      "/rapi/v7/community_uploads/",
                                    )
                                    .concat(r),
                                )
                              );
                            case 3:
                              return (
                                (o = n.sent),
                                (data = o.data),
                                (n.next = 4),
                                t.$del(
                                  ""
                                    .concat(
                                      t.$getApiBaseUrl("growth"),
                                      "/api/v9/community_uploads/",
                                    )
                                    .concat(r),
                                  { x_license: data.community_uploads_license },
                                )
                              );
                            case 4:
                              ((c = n.sent),
                                c.data,
                                t.$emit("onDestroy", r),
                                (n.next = 6));
                              break;
                            case 5:
                              ((n.prev = 5),
                                (l = n.catch(2)),
                                t.$S.alert(
                                  "Failed to delete this image.  Contact us to figure out why it did not work.",
                                ),
                                e(l));
                            case 6:
                              t.$S.is_loading = !1;
                            case 7:
                            case "end":
                              return n.stop();
                          }
                      },
                      n,
                      null,
                      [[2, 5]],
                    );
                  }),
                )();
              },
            },
            computed: {
              is_image: function () {
                var t = this.data.extension;
                return [
                  "png",
                  "jpg",
                  "jpeg",
                  "webp",
                  "gif",
                  "bmp",
                  "svg",
                  "apng",
                ].includes(t);
              },
              is_video: function () {
                var t = this.data.extension;
                return ["webm", "mp4", "mpeg", "ts"].includes(t);
              },
              container_style: function () {
                var data = this.data,
                  t = 144;
                return (
                  this.$S.browser_width >= 960 && (t = 188),
                  {
                    width: "".concat((data.width * t) / data.height, "px"),
                    flexGrow: "".concat((data.width * t) / data.height),
                  }
                );
              },
              video_or_audio_url: function () {
                return this.$App.directUrl(this.data.canonical_url);
              },
              image_src: function () {
                if (this.is_mounted) {
                  if (this.$refs.container) {
                    if (this.data.thumbnail_url) return this.data.thumbnail_url;
                    if (/\?/.test(this.data.proxy_url))
                      return this.data.proxy_url;
                    var t = this.data.id % 4,
                      e = this.data.proxy_url.replace("https://", "");
                    return "https://i"
                      .concat(t, ".wp.com/")
                      .concat(e)
                      .concat("?h=200");
                  }
                  return transparent;
                }
              },
              click_url: function () {
                return this.data.proxy_url;
              },
            },
          }),
        l = r(15),
        component = Object(l.a)(
          c,
          function () {
            var t = this,
              e = t._self._c;
            return e(
              t.is_video || t.is_image ? "a" : "div",
              {
                ref: "container",
                tag: "component",
                staticClass: "cuc",
                style: t.container_style,
                attrs: { href: t.click_url, target: "_blank", rel: "noopener" },
              },
              [
                e("div", {
                  style: {
                    paddingBottom: "".concat(
                      (t.data.height / t.data.width) * 100,
                      "%",
                    ),
                  },
                }),
                t._v(" "),
                t.is_image
                  ? e("img", {
                      staticClass: "cuc__content",
                      attrs: { src: t.image_src, referrerpolicy: "origin" },
                    })
                  : e("div"),
                t._v(" "),
                e("div", {
                  class: "indicator ".concat(t.data.channel_name, "-bg"),
                }),
                t._v(" "),
                t.is_video || t.is_image
                  ? e("div", { staticClass: "credits" }, [
                      e("div", { staticClass: "username cut_text" }, [
                        e("span", [t._v("by")]),
                        t._v(" " + t._s(t.data.username)),
                      ]),
                    ])
                  : t._e(),
                t._v(" "),
                t.isDeleteable
                  ? e(
                      "div",
                      {
                        staticClass:
                          "delete_btn flex align-center justify-center",
                        on: {
                          click: function (e) {
                            return (
                              e.stopPropagation(),
                              e.preventDefault(),
                              t.confirmDestroy()
                            );
                          },
                        },
                      },
                      [e("v-icon", [t._v("mdi-trash-can")])],
                      1,
                    )
                  : t._e(),
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
    312: function (t, e, n) {
      "use strict";
      n.r(e);
      n(22);
      var r = {
          components: { "htv-lazy": n(308).default },
          props: ["item", "clickable", "onActivateCallback"],
          beforeCreate: function () {},
          data: function () {
            return { is_rater_card_visible: !1, is_playlists_card_visible: !1 };
          },
          methods: {
            onMouseDown: function (t) {
              if (this.onActivateCallback)
                return (
                  t.preventDefault(),
                  t.stopPropagation(),
                  this.onActivateCallback(this.item),
                  !1
                );
              this.$emit("mousedown", t, this.onActivate);
            },
            onActivate: function () {
              this.$router.push("/videos/hentai/".concat(this.item.slug));
            },
          },
          computed: {
            on_drag_start: function () {
              return void 0 === this.clickable ? "return false" : null;
            },
            event: function () {
              return void 0 === this.clickable ? "" : "click";
            },
            cover_url: function () {
              return void 0 === this.item ? "" : this.item.cover_url;
            },
          },
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            this._self._c;
            return this._m(0);
          },
          [
            function () {
              var t = this,
                e = t._self._c;
              return e(
                "v-card",
                { staticClass: "elevation-3 mb-3 hvc" },
                [
                  e(
                    "router-link",
                    {
                      class: t.$App.is_ios ? "" : "no-touch",
                      attrs: {
                        to: "/videos/hentai/".concat(t.item.slug),
                        draggable: "false",
                        alt: t.item.name,
                        title: "Watch ".concat(
                          t.item.name,
                          " hentai stream online HD 1080p, 720p",
                        ),
                        event: t.event,
                        ondragstart: t.on_drag_start,
                      },
                    },
                    [
                      e(
                        "div",
                        { on: { mousedown: t.onMouseDown } },
                        [
                          e("v-responsive", { staticClass: "hvc__media" }, [
                            e(
                              "div",
                              { staticClass: "hvc__media__cover-container" },
                              [
                                e(
                                  "div",
                                  {
                                    staticClass:
                                      "hvc__media__cover-aspect-ratio",
                                  },
                                  [
                                    e(
                                      "htv-lazy",
                                      { staticClass: "hvc__media__cover" },
                                      [
                                        e("img", {
                                          staticClass:
                                            "hvc__media__cover__image",
                                          attrs: {
                                            src: t.cover_url,
                                            alt: t.item.name,
                                            title: "Watch ".concat(
                                              t.item.name,
                                              " hentai stream online HD 1080p, 720p",
                                            ),
                                            draggable: "false",
                                            referrerpolicy: "origin",
                                          },
                                        }),
                                      ],
                                    ),
                                    t._v(" "),
                                    e("div", {
                                      staticClass: "hvc__media__cover-glass",
                                    }),
                                  ],
                                  1,
                                ),
                              ],
                            ),
                          ]),
                          t._v(" "),
                          e("v-card-title", [
                            e(
                              "div",
                              {
                                staticClass:
                                  "hvc__content flex column justify-center align-center",
                              },
                              [
                                e("v-spacer"),
                                t._v(" "),
                                e("div", { staticClass: "hv-title" }, [
                                  t._v(
                                    "\n            " +
                                      t._s(
                                        t._f("truncate")(t.item.name, "50"),
                                      ) +
                                      "\n          ",
                                  ),
                                ]),
                                t._v(" "),
                                t.$slots.default
                                  ? t._t("default")
                                  : e(
                                      "div",
                                      { staticClass: "hv-subtitle" },
                                      [
                                        e("v-icon", [t._v("mdi-eye-outline")]),
                                        t._v(
                                          "\n            " +
                                            t._s(
                                              t.item.views.toLocaleString(
                                                "en-US",
                                              ),
                                            ) +
                                            "\n          ",
                                        ),
                                      ],
                                      1,
                                    ),
                                t._v(" "),
                                e("v-spacer"),
                              ],
                              2,
                            ),
                          ]),
                        ],
                        1,
                      ),
                    ],
                  ),
                ],
                1,
              );
            },
          ],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    330: function (t, e, n) {
      "use strict";
      n.r(e);
      (n(28),
        n(77),
        n(79),
        n(80),
        n(54),
        n(22),
        n(14),
        n(19),
        n(39),
        n(40),
        n(47));
      function r(t, e) {
        var n =
          ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
          t["@@iterator"];
        if (!n) {
          if (
            Array.isArray(t) ||
            (n = (function (t, a) {
              if (t) {
                if ("string" == typeof t) return o(t, a);
                var e = {}.toString.call(t).slice(8, -1);
                return (
                  "Object" === e && t.constructor && (e = t.constructor.name),
                  "Map" === e || "Set" === e
                    ? Array.from(t)
                    : "Arguments" === e ||
                        /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                      ? o(t, a)
                      : void 0
                );
              }
            })(t)) ||
            (e && t && "number" == typeof t.length)
          ) {
            n && (t = n);
            var r = 0,
              c = function () {};
            return {
              s: c,
              n: function () {
                return r >= t.length
                  ? { done: !0 }
                  : { done: !1, value: t[r++] };
              },
              e: function (t) {
                throw t;
              },
              f: c,
            };
          }
          throw new TypeError(
            "Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.",
          );
        }
        var l,
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
            ((u = !0), (l = t));
          },
          f: function () {
            try {
              a || null == n.return || n.return();
            } finally {
              if (u) throw l;
            }
          },
        };
      }
      function o(t, a) {
        (null == a || a > t.length) && (a = t.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
        return n;
      }
      var c = {
          beforeCreate: function () {},
          data: function () {
            return { clamped_opacity: 1 };
          },
          methods: {
            getCurrentLandingEventPeriod: function () {
              var t = this.$config.LANDING_EVENT;
              if (!t || !t.periods || !t.periods.length) return {};
              var e,
                n = Date.now(),
                o = r(t.periods);
              try {
                for (o.s(); !(e = o.n()).done; ) {
                  var c = e.value,
                    l = new Date(c.start).getTime(),
                    _ = new Date(c.end).getTime();
                  if (n >= l && n <= _) return c;
                }
              } catch (t) {
                o.e(t);
              } finally {
                o.f();
              }
              return {};
            },
          },
          computed: {
            hero_image_src: function () {
              if (this.$App.user_agent && this.$App.is_bot)
                return "https://hanime-cdn.com/images/site-bg-1.jpg";
              if (
                (this.$S.user && this.$S.user.is_able_to_access_premium) ||
                !this.$config.LANDING_EVENT
              )
                return "https://hanime-cdn.com/images/bg3d-1900.930.min.jpg";
              var t = this.getCurrentLandingEventPeriod();
              return t && t.img_src
                ? t.img_src
                : "https://hanime-cdn.com/images/bg3d-1900.930.min.jpg";
            },
            translate_3d: function () {
              return "translate3d(0, ".concat(0.4 * this.$S.scrollY, "px, 0)");
            },
            opacity: function () {
              if (!this.$S.scrollY || !this.$el) return this.clamped_opacity;
              var t = 0.8 * this.$el.clientHeight;
              return parseFloat(
                (this.clamped_opacity * (t - this.$S.scrollY)) / t,
              );
            },
          },
        },
        l = n(15),
        component = Object(l.a)(
          c,
          function () {
            var t = this,
              e = t._self._c;
            return e("div", { staticClass: "parallax-container" }, [
              e("div", { staticClass: "parallax" }, [
                t.$App.is_other || t.$App.is_mac
                  ? e("img", {
                      staticClass: "hero_image",
                      style: { transform: t.translate_3d, opacity: t.opacity },
                      attrs: { src: t.hero_image_src },
                    })
                  : t._m(0),
                t._v(" "),
                e(
                  "div",
                  { staticClass: "parallax-content" },
                  [
                    t._t("default"),
                    t._v(" "),
                    e(
                      "transition",
                      { attrs: { name: "fade" } },
                      [
                        0 == t.$S.scrollY
                          ? e("v-icon", { staticClass: "content__indicator" }, [
                              t._v("mdi-chevron-down"),
                            ])
                          : t._e(),
                      ],
                      1,
                    ),
                  ],
                  2,
                ),
              ]),
            ]);
          },
          [
            function () {
              return (0, this._self._c)("img", {
                attrs: { src: this.hero_image_src },
              });
            },
          ],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    331: function (t, e, n) {
      "use strict";
      n.r(e);
      var r = {
          components: { "hentai-video-card": n(312).default },
          props: ["items", "itemType"],
          beforeCreate: function () {},
          data: function () {
            return {
              is_mousedown: !1,
              x_mousedown: 0,
              x_movement: 0,
              x: 0,
              left: 0,
              max_left: 0,
              focused_item_on_activate_callback: null,
            };
          },
          mounted: function () {
            var t = this.$refs.carousel__slider;
            if (t) {
              var e = t.clientWidth,
                n = t.scrollWidth - e;
              this.max_left = n;
            }
          },
          methods: {
            carouselNav: function (t, e) {
              var n = this.$refs.carousel__slider,
                r = n.clientWidth,
                o = this.left;
              "touchstart" == t.type &&
                (o = (n = this.$refs.carousel).scrollLeft);
              var c = 0;
              if ("next" == e) c = Math.min(o + r, this.max_left);
              else {
                c = Math.max(o - r, 0);
              }
              "touchstart" == t.type || (this.left = c);
            },
            onCarouselItemMouseDown: function (t, e) {
              this.focused_item_on_activate_callback = e;
            },
            onMouseDown: function (t) {
              ((this.is_mousedown = !0),
                (this.x = this.left + t.screenX),
                (this.x_mousedown = t.screenX),
                (this.x_movement = 0));
            },
            onMouseUp: function (t) {
              ((this.is_mousedown = !1),
                this.x_movement <= 50 &&
                  1 === t.which &&
                  this.focused_item_on_activate_callback &&
                  "function" == typeof this.focused_item_on_activate_callback &&
                  this.focused_item_on_activate_callback());
            },
            onMouseMove: function (t) {
              if (this.is_mousedown) {
                var e = t.screenX - this.x;
                ((this.left = Math.min(Math.max(0, -e), this.max_left)),
                  (e = Math.abs(t.screenX - this.x_mousedown)),
                  (this.x_movement += e));
              }
            },
          },
          computed: {
            carousel__slider_style: function () {
              var style = {
                transform: "translate3d(-".concat(this.left, "px, 0px, 0px)"),
              };
              return (
                this.is_mousedown
                  ? (style.transition = "none")
                  : (style.transition = "all 0.3s ease"),
                style
              );
            },
            is_prev_arrow_enabled: function () {
              return this.left > 0;
            },
            is_next_arrow_enabled: function () {
              return this.left < this.max_left;
            },
          },
        },
        o = n(15),
        component = Object(o.a)(
          r,
          function () {
            var t = this,
              e = t._self._c;
            return e("div", { staticClass: "htv-carousel noselect" }, [
              this.$slots.default
                ? e(
                    "div",
                    {
                      staticClass:
                        "htv-carousel__header flex row justify-left align-center wrap mb-3",
                    },
                    [
                      t._t("default"),
                      t._v(" "),
                      t.$S.is_mounted && t.$App.is_other
                        ? e(
                            "div",
                            {
                              staticClass:
                                "htv-carousel__header__nav_container",
                            },
                            [
                              e(
                                "v-btn",
                                {
                                  staticClass:
                                    "htv-carousel__header__nav-arrow htv-carousel__header__nav-arrow-left",
                                  attrs: {
                                    outline: "",
                                    large: "",
                                    disabled: !t.is_prev_arrow_enabled,
                                  },
                                  on: {
                                    click: function (e) {
                                      return t.carouselNav(e, "prev");
                                    },
                                  },
                                },
                                [e("v-icon", [t._v("mdi-chevron-left")])],
                                1,
                              ),
                              t._v(" "),
                              e(
                                "v-btn",
                                {
                                  staticClass:
                                    "htv-carousel__header__nav-arrow htv-carousel__header__nav-arrow-right",
                                  attrs: {
                                    outline: "",
                                    large: "",
                                    disabled: !t.is_next_arrow_enabled,
                                  },
                                  on: {
                                    click: function (e) {
                                      return t.carouselNav(e, "next");
                                    },
                                  },
                                },
                                [e("v-icon", [t._v("mdi-chevron-right")])],
                                1,
                              ),
                            ],
                            1,
                          )
                        : t._e(),
                    ],
                    2,
                  )
                : t._e(),
              t._v(" "),
              e(
                "div",
                {
                  ref: "carousel",
                  class: [
                    "htv-carousel__scrolls",
                    { "htv-carousel__desktop": t.$App.is_other },
                  ],
                  on: {
                    mousedown: t.onMouseDown,
                    mouseup: t.onMouseUp,
                    mouseenter: function (e) {
                      t.is_mousedown = !1;
                    },
                    mousemove: t.onMouseMove,
                  },
                },
                [
                  e(
                    "div",
                    {
                      ref: "carousel__slider",
                      staticClass: "htv-carousel__slider",
                      style: t.carousel__slider_style,
                    },
                    t._l(t.items, function (n, i) {
                      return e(t.itemType, {
                        key: "carousel-item-".concat(i),
                        tag: "component",
                        staticClass: "item",
                        attrs: { item: n },
                        on: { mousedown: t.onCarouselItemMouseDown },
                      });
                    }),
                    1,
                  ),
                ],
              ),
            ]);
          },
          [],
          !1,
          null,
          null,
          null,
        );
      e.default = component.exports;
    },
    361: function (t, e, n) {
      "use strict";
      n.r(e);
      n(45);
      var r = n(5),
        o =
          (n(28),
          n(77),
          n(79),
          n(159),
          n(80),
          n(121),
          n(54),
          n(160),
          n(22),
          n(29),
          n(14),
          n(19),
          n(39),
          n(40),
          n(81),
          n(47),
          n(27),
          n(1)),
        c = n.n(o),
        _ = n(78),
        d = n.n(_);
      function h(t, e) {
        var n =
          ("undefined" != typeof Symbol && t[Symbol.iterator]) ||
          t["@@iterator"];
        if (!n) {
          if (
            Array.isArray(t) ||
            (n = (function (t, a) {
              if (t) {
                if ("string" == typeof t) return v(t, a);
                var e = {}.toString.call(t).slice(8, -1);
                return (
                  "Object" === e && t.constructor && (e = t.constructor.name),
                  "Map" === e || "Set" === e
                    ? Array.from(t)
                    : "Arguments" === e ||
                        /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(e)
                      ? v(t, a)
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
      function v(t, a) {
        (null == a || a > t.length) && (a = t.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = t[e];
        return n;
      }
      var m = {
          components: {
            parallax: n(330).default,
            "htv-lazy": n(308).default,
            "htv-carousel": n(331).default,
            "htv-ad": n(309).default,
            "community-upload-card": n(311).default,
          },
          fetch: function (t) {
            return Object(r.a)(
              regeneratorRuntime.mark(function e() {
                return regeneratorRuntime.wrap(function (e) {
                  for (;;)
                    switch ((e.prev = e.next)) {
                      case 0:
                        return (
                          l(
                            "[".concat(
                              is_server ? "server" : "client",
                              "][landing::fetch] context.route.path:",
                            ),
                            t.route.path,
                          ),
                          (e.next = 1),
                          t.$S.doFetch(
                            "".concat(t.$getApiBaseUrl(), "/api/v8/landing"),
                            "landing",
                            t,
                            "async",
                          )
                        );
                      case 1:
                        t.$S.page_name = "Home";
                      case 2:
                      case "end":
                        return e.stop();
                    }
                }, e);
              }),
            )();
          },
          head: function () {
            return this.getHead();
          },
          metaInfo: function () {
            return { title: this.getHead().title };
          },
          beforeCreate: function () {},
          created: function () {
            this.processLandingData();
          },
          data: function () {
            return {
              ticking: !1,
              last_known_scroll_position: 0,
              images_channel_names: ["media", "nsfw-general"],
              community_uploads: [],
            };
          },
          beforeMount: function () {
            (this.$S.$on("landing_FETCH_SUCCESS", this.processLandingData),
              this.$S.$on("APP_FULLY_MOUNTED", this.getCommunityUploads));
          },
          mounted: function () {
            (this.$scrollTop(), this.getCommunityUploads());
          },
          beforeDestroy: function () {
            (this.$S.$off("landing_FETCH_SUCCESS", this.processLandingData),
              this.$S.$off("APP_FULLY_MOUNTED", this.getCommunityUploads));
          },
          methods: {
            getHead: function () {
              return {
                title:
                  "Watch Free Hentai Video Streams Online in 720p, 1080p HD - hanime.tv",
                meta: [
                  {
                    hid: "description",
                    name: "description",
                    content:
                      "Watch hentai online free download HD on mobile phone tablet laptop desktop.  Stream online, regularly released uncensored, subbed, in 720p and 1080p!",
                  },
                ],
              };
            },
            processLandingData: function () {
              var t = this.$S.data.landing;
              t &&
                ((t.hvs = {}),
                d.a.each(this.$S.data.landing.hentai_videos, function (e) {
                  t.hvs[e.id] = e;
                }),
                (t.processed_sections = {}),
                c.a.set(this.$S.data.landing, "processed_sections", {}),
                d.a.each(this.$S.data.landing.sections, function (section) {
                  var e = [],
                    n = section.hentai_video_ids;
                  (d.a.each(n, function (n) {
                    var r = t.hvs[n];
                    e.push(r);
                  }),
                    (t.processed_sections[section.title] = e));
                }),
                this.$S.user_setting &&
                  this.$S.user_setting.images_channels &&
                  (this.images_channel_names = JSON.parse(
                    this.$S.user_setting.images_channels,
                  )));
            },
            getCommunityUploads: function () {
              var t = this;
              return Object(r.a)(
                regeneratorRuntime.mark(function e() {
                  var n, r, data, o, c;
                  return regeneratorRuntime.wrap(
                    function (e) {
                      for (;;)
                        switch ((e.prev = e.next)) {
                          case 0:
                            if (t.$S.is_mounted) {
                              e.next = 1;
                              break;
                            }
                            return e.abrupt("return");
                          case 1:
                            return (
                              (n = t.getChannelNamesParam()),
                              (e.prev = 2),
                              (e.next = 3),
                              t.$get(
                                ""
                                  .concat(
                                    t.$getApiBaseUrl("growth"),
                                    "/api/v9/community_uploads?",
                                  )
                                  .concat(n, "&kind=landing&loc=")
                                  .concat(window.top.location.origin),
                              )
                            );
                          case 3:
                            ((r = e.sent),
                              (data = r.data),
                              data.meta,
                              (o = data.data).splice(12, 12),
                              (t.community_uploads = o),
                              (e.next = 5));
                            break;
                          case 4:
                            ((e.prev = 4), (c = e.catch(2)), l(c));
                          case 5:
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
            getChannelNamesParam: function () {
              var t = this.images_channel_names,
                e = [];
              return (
                d.a.each(t, function (t) {
                  e.push("channel_name__in[]=".concat(t));
                }),
                e.join("&")
              );
            },
            onScroll: function (t) {
              ((this.last_known_scroll_position = window.scrollY),
                this.requestTick());
            },
            requestTick: function () {
              (this.ticking || requestAnimationFrame(this.updateScrollY),
                (this.ticking = !0));
            },
            updateScrollY: function () {
              ((this.$S.scrollY = this.last_known_scroll_position),
                (this.ticking = !1));
            },
            timeAgoForSection: function (t) {
              var e =
                (this.$S.data.landing.processed_sections[t] || [])[0] || {};
              return this.timeAgoText(e.created_at_unix);
            },
            timeAgoText: function () {
              var t =
                  arguments.length > 0 && void 0 !== arguments[0]
                    ? arguments[0]
                    : new Date() / 1e3,
                e = new Date(),
                n = new Date(1e3 * t),
                r = parseInt((e - n) / 1e3 / 3600),
                o = parseInt(r / 24);
              r %= 24;
              var c = o > 1 ? "days" : "day",
                l = 0 === o ? "" : "".concat(o, " ").concat(c, " "),
                _ = r > 1 ? "hours" : "hour";
              return "".concat(l).concat(r, " ").concat(_, " ago");
            },
            miniResetSearch: function (t, e) {
              ((this.$S.search.search_text = ""),
                (this.$S.search.order_by = t),
                (this.$S.search.ordering = e),
                (this.$S.search.cache_sorting_config =
                  Search.buildSortingConfig(this.$S)),
                d.a.each(this.$S.search.tags, function (t) {
                  t.is_active = !1;
                }),
                (this.$S.search.active_tags_count = 0),
                d.a.each(this.$S.search.brands, function (br) {
                  br.is_active = !1;
                }),
                (this.$S.search.active_brands_count = 0),
                d.a.each(this.$S.search.blacklisted_tags, function (t) {
                  t.is_active = !1;
                }),
                (this.$S.search.active_blacklisted_tags_count = 0));
            },
            carouselAction: function (t) {
              switch (t) {
                case 1:
                  (this.miniResetSearch("created_at_unix", "desc"),
                    this.$EVT.$emit(this.$EVT.GOTO, "/search"));
                  break;
                case 2:
                  (this.miniResetSearch("released_at_unix", "desc"),
                    this.$EVT.$emit(this.$EVT.GOTO, "/search"));
                  break;
                case 3:
                  this.$EVT.$emit(this.$EVT.GOTO, "/browse/trending");
                  break;
                case 5:
                  this.$EVT.$emit(
                    this.$EVT.GOTO,
                    this.myLikedVideosPlaylistUrl(),
                  );
                  break;
                default:
                  this.$EVT.$emit(this.$EVT.GOTO, "/browse/random");
              }
            },
            myLikedVideosPlaylistUrl: function () {
              var t = d.a.find(this.$S.playlists, {
                is_mutable: !1,
                title: "Liked Videos",
              });
              return t ? "/playlists/".concat(t.slug) : "/";
            },
          },
          computed: {
            pg: function () {
              return this.$S.data.landing || {};
            },
            is_event_ad_visible_for_user: function () {
              return (
                (!this.$S.user || !this.$S.user.is_able_to_access_premium) &&
                !!this.$config.LANDING_EVENT &&
                !!this.current_landing_event_period
              );
            },
            current_landing_event_period: function () {
              var t = this.$config.LANDING_EVENT;
              if (!t) return null;
              var e,
                n = Date.now(),
                r = h(t.periods || []);
              try {
                for (r.s(); !(e = r.n()).done; ) {
                  var o = e.value,
                    c = new Date(o.start).getTime(),
                    l = new Date(o.end).getTime();
                  if (n >= c && n < l) return o;
                }
              } catch (t) {
                r.e(t);
              } finally {
                r.f();
              }
              return null;
            },
          },
        },
        f = m,
        y = n(15),
        component = Object(y.a)(
          f,
          function () {
            var t,
              e = this,
              n = e._self._c;
            return n(
              "div",
              { staticClass: "landing full-page-height" },
              [
                n(
                  "parallax",
                  [
                    n("h1", [e._v("Watch Free HD Hentai & Anime Videos")]),
                    e._v(" "),
                    n("span", { staticClass: "subtext" }, [
                      e._v("Enjoy your\n    "),
                      n("span", { staticClass: "highlight" }, [
                        e._v("unlimited"),
                      ]),
                      e._v(
                        "\n    hentai & anime collection.  We are the definitive source\n    for the best curated 720p / 1080p HD hentai videos, viewable\n    by mobile phone and tablet, for free.",
                      ),
                    ]),
                    e._v(" "),
                    e.is_event_ad_visible_for_user
                      ? [
                          n(
                            "div",
                            {
                              staticClass:
                                "event-container flex column justify-center align-center",
                            },
                            [
                              n("div", { staticClass: "event-divider mb-3" }),
                              e._v(" "),
                              n("div", { staticClass: "event-title mb-1" }, [
                                e._v("Nutaku Gaming Portal"),
                              ]),
                              e._v(" "),
                              n(
                                "a",
                                {
                                  directives: [
                                    { name: "ripple", rawName: "v-ripple" },
                                  ],
                                  staticClass: "event-btn elevation-24",
                                  attrs: {
                                    href:
                                      null ===
                                        (t = e.current_landing_event_period) ||
                                      void 0 === t
                                        ? void 0
                                        : t.url,
                                    target: "_blank",
                                  },
                                },
                                [
                                  n("div", { staticClass: "event-btn-border" }),
                                  e._v(" "),
                                  n(
                                    "div",
                                    {
                                      staticClass:
                                        "event-btn-content flex column justify-center align-center fill-height noselect",
                                    },
                                    [
                                      n("div", { staticClass: "ebc-title" }, [
                                        e._v("SIGN UP"),
                                      ]),
                                      e._v(" "),
                                      n(
                                        "div",
                                        { staticClass: "ebc-subtitle" },
                                        [e._v("Nutaku Gaming Portal")],
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                              e._v(" "),
                              n(
                                "div",
                                {
                                  staticClass:
                                    "grey--text text--darken-1 body-2 mt-1",
                                },
                                [e._v("Sponsored Ad")],
                              ),
                              e._v(" "),
                              n(
                                "div",
                                {
                                  staticClass:
                                    "grey--text text--lighten-1 event-subtitle mt-1 mb-3",
                                },
                                [e._v("Over 500 adult games")],
                              ),
                            ],
                          ),
                        ]
                      : e._e(),
                    e._v(" "),
                    n("div", { staticClass: "gradient" }),
                  ],
                  2,
                ),
                e._v(" "),
                e.$S.data.landing
                  ? n(
                      "div",
                      { staticClass: "landing__content" },
                      [
                        e._l(e.pg.sections.length, function (t) {
                          return n(
                            "div",
                            {
                              key: "landing-htv-carousel-container-".concat(
                                t - 1,
                              ),
                            },
                            [
                              1 == t
                                ? [
                                    n(
                                      "htv-carousel",
                                      {
                                        staticClass: "mb-5",
                                        attrs: {
                                          items:
                                            e.$S.data.landing
                                              .processed_sections[
                                              e.$S.data.landing.sections[t - 1]
                                                .title
                                            ],
                                          "item-type": "hentai-video-card",
                                        },
                                      },
                                      [
                                        n(
                                          "div",
                                          {
                                            staticClass:
                                              "htv-carousel__header__title flex column",
                                          },
                                          [
                                            n("span", [
                                              e._v(
                                                e._s(
                                                  e.$S.data.landing.sections[
                                                    t - 1
                                                  ].title,
                                                ),
                                              ),
                                            ]),
                                            e._v(" "),
                                            t < 3
                                              ? n(
                                                  "span",
                                                  {
                                                    staticClass:
                                                      "htv-carousel__header__title__subtitle",
                                                  },
                                                  [
                                                    e._v(
                                                      "\n              " +
                                                        e._s(
                                                          e.timeAgoForSection(
                                                            e.$S.data.landing
                                                              .sections[t - 1]
                                                              .title,
                                                          ),
                                                        ) +
                                                        "\n            ",
                                                    ),
                                                  ],
                                                )
                                              : e._e(),
                                          ],
                                        ),
                                        e._v(" "),
                                        n(
                                          "v-btn",
                                          {
                                            staticClass:
                                              "htv-carousel__header__all",
                                            attrs: { outline: "", large: "" },
                                            on: {
                                              click: function (t) {
                                                return e.carouselAction(1);
                                              },
                                            },
                                          },
                                          [
                                            e._v(
                                              "\n            ALL\n          ",
                                            ),
                                          ],
                                        ),
                                      ],
                                      1,
                                    ),
                                    e._v(" "),
                                    n("htv-ad", {
                                      staticClass: "mb-5",
                                      staticStyle: { margin: "0 auto" },
                                      attrs: {
                                        placement: "adhesion_0",
                                        data: e.$S.data.landing.bs,
                                      },
                                    }),
                                    e._v(" "),
                                    n(
                                      "htv-lazy",
                                      {
                                        staticClass:
                                          "htv-carousel-container mb-5",
                                      },
                                      [
                                        n(
                                          "div",
                                          {
                                            staticClass:
                                              "community_uploads flex column align-center",
                                          },
                                          [
                                            n(
                                              "div",
                                              {
                                                staticClass:
                                                  "cu__header flex row wrap",
                                              },
                                              [
                                                n(
                                                  "div",
                                                  {
                                                    staticClass:
                                                      "flex column align-left",
                                                  },
                                                  [
                                                    n(
                                                      "div",
                                                      {
                                                        staticClass:
                                                          "hidden-sm-and-up",
                                                      },
                                                      [e._v("Recent Images")],
                                                    ),
                                                    e._v(" "),
                                                    n(
                                                      "div",
                                                      {
                                                        staticClass:
                                                          "hidden-xs-only",
                                                      },
                                                      [
                                                        e._v(
                                                          "Recent Image Uploads",
                                                        ),
                                                      ],
                                                    ),
                                                    e._v(" "),
                                                    n(
                                                      "div",
                                                      {
                                                        staticClass:
                                                          "legend__container flex wrap",
                                                      },
                                                      e._l(
                                                        e.images_channel_names,
                                                        function (t, i) {
                                                          return n(
                                                            "div",
                                                            {
                                                              key: "ic-".concat(
                                                                i,
                                                              ),
                                                              staticClass:
                                                                "legend",
                                                            },
                                                            [
                                                              n("div", {
                                                                class:
                                                                  "dot ".concat(
                                                                    t,
                                                                    "-bg",
                                                                  ),
                                                              }),
                                                              e._v(
                                                                " #" +
                                                                  e._s(t) +
                                                                  "\n                  ",
                                                              ),
                                                            ],
                                                          );
                                                        },
                                                      ),
                                                      0,
                                                    ),
                                                  ],
                                                ),
                                                e._v(" "),
                                                n("v-spacer"),
                                                e._v(" "),
                                                n(
                                                  "v-btn",
                                                  {
                                                    staticClass:
                                                      "htv-carousel__header__all",
                                                    attrs: {
                                                      outline: "",
                                                      large: "",
                                                      to: "/browse/images",
                                                    },
                                                  },
                                                  [
                                                    e._v(
                                                      "\n                All\n              ",
                                                    ),
                                                  ],
                                                ),
                                              ],
                                              1,
                                            ),
                                            e._v(" "),
                                            n(
                                              "div",
                                              {
                                                staticClass:
                                                  "mt-3 cuc_container",
                                              },
                                              e._l(
                                                e.community_uploads,
                                                function (t, i) {
                                                  return n(
                                                    "community-upload-card",
                                                    {
                                                      key: "cu-"
                                                        .concat(i, "-")
                                                        .concat(t.msg_id, "-")
                                                        .concat(
                                                          t.attachment_id,
                                                        ),
                                                      attrs: { data: t },
                                                    },
                                                  );
                                                },
                                              ),
                                              1,
                                            ),
                                            e._v(" "),
                                            n(
                                              "v-btn",
                                              {
                                                staticClass: "more_btn",
                                                attrs: {
                                                  to: "/browse/images",
                                                  flat: "",
                                                  block: "",
                                                  color: "primary",
                                                },
                                              },
                                              [e._v("Show More")],
                                            ),
                                          ],
                                          1,
                                        ),
                                      ],
                                    ),
                                  ]
                                : [
                                    n("htv-ad", {
                                      staticClass: "mb-5",
                                      staticStyle: { margin: "0 auto" },
                                      attrs: {
                                        placement: "adhesion_".concat(t - 1),
                                        data: e.$S.data.landing.bs,
                                      },
                                    }),
                                    e._v(" "),
                                    n(
                                      "div",
                                      {
                                        staticClass:
                                          "htv-carousel-container mb-5",
                                      },
                                      [
                                        n(
                                          "htv-carousel",
                                          {
                                            attrs: {
                                              items:
                                                e.$S.data.landing
                                                  .processed_sections[
                                                  e.$S.data.landing.sections[
                                                    t - 1
                                                  ].title
                                                ],
                                              "item-type": "hentai-video-card",
                                            },
                                          },
                                          [
                                            n(
                                              "div",
                                              {
                                                staticClass:
                                                  "htv-carousel__header__title flex column",
                                              },
                                              [
                                                n("span", [
                                                  e._v(
                                                    e._s(
                                                      e.$S.data.landing
                                                        .sections[t - 1].title,
                                                    ),
                                                  ),
                                                ]),
                                                e._v(" "),
                                                t < 3
                                                  ? n(
                                                      "span",
                                                      {
                                                        staticClass:
                                                          "htv-carousel__header__title__subtitle",
                                                      },
                                                      [
                                                        e._v(
                                                          "\n                " +
                                                            e._s(
                                                              e.timeAgoForSection(
                                                                e.$S.data
                                                                  .landing
                                                                  .sections[
                                                                  t - 1
                                                                ].title,
                                                              ),
                                                            ) +
                                                            "\n              ",
                                                        ),
                                                      ],
                                                    )
                                                  : 3 == t
                                                    ? n(
                                                        "span",
                                                        {
                                                          staticClass:
                                                            "htv-carousel__header__title__subtitle",
                                                        },
                                                        [e._v("Past 30 days")],
                                                      )
                                                    : e._e(),
                                              ],
                                            ),
                                            e._v(" "),
                                            n(
                                              "v-btn",
                                              {
                                                staticClass:
                                                  "htv-carousel__header__all",
                                                attrs: {
                                                  outline: "",
                                                  large: "",
                                                  disabled:
                                                    4 == t && e.$S.is_loading,
                                                },
                                                on: {
                                                  click: function (n) {
                                                    return e.carouselAction(t);
                                                  },
                                                },
                                              },
                                              [
                                                4 == t
                                                  ? e._o(
                                                      [
                                                        n("v-icon", [
                                                          e._v(
                                                            "mdi-shuffle-variant",
                                                          ),
                                                        ]),
                                                      ],
                                                      0,
                                                      "landing-htv-carousel-container-".concat(
                                                        t - 1,
                                                      ),
                                                    )
                                                  : e._o(
                                                      [
                                                        e._v(
                                                          "\n                ALL\n              ",
                                                        ),
                                                      ],
                                                      1,
                                                      "landing-htv-carousel-container-".concat(
                                                        t - 1,
                                                      ),
                                                    ),
                                              ],
                                              2,
                                            ),
                                          ],
                                          1,
                                        ),
                                      ],
                                      1,
                                    ),
                                  ],
                            ],
                            2,
                          );
                        }),
                        e._v(" "),
                        n("htv-ad", {
                          staticClass: "mb-5",
                          staticStyle: { margin: "0 auto" },
                          attrs: {
                            placement: "footer_0",
                            data: e.$S.data.landing.bs,
                          },
                        }),
                      ],
                      2,
                    )
                  : e._e(),
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
  },
]);
