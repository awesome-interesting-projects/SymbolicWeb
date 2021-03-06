(ns symbolicweb.examples.nostdal-org
  (:use symbolicweb.core)
  (:use hiccup.core)
  (:require [garden.core :refer [css style]]))



(defn homepage [request session]
  (let [root-widget (mk-bte :id "_body" :root-widget? true)
        viewport (mk-Viewport request session root-widget :page-title "nostdal.org")]
    (add-resource viewport :css "sw/css/common.css")
    (add-rest-head viewport "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no' />")
    (add-rest-head viewport
      (html
       [:style (css [:body {:background-color "rgb(171,191,181)"
                            :color 'black
                            :font-family "'DejaVu Sans', sans-serif"}]
                    [:a {:color 'black}]
                    [:li {:padding-bottom "0.5em"}])]))
    (add-rest-head viewport "<link href='data:image/x-icon;base64,AAABAAEAEBAQAAAAAAAoAQAAFgAAACgAAAAQAAAAIAAAAAEABAAAAAAAgAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAjIyMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAQAAABAQAAAQAAAAAAAAAAAAAAABAAAAAAAAAAAAAQAAAAAAABAAEAAAAAAAAAAAAAAAAAABAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAD//wAA54cAAOOHAADzvwAA878AAPk/AAD5PwAA/H8AAPx/AAD+/wAA/v8AAP7/AAD8/wAA9P8AAPH/AAD//wAA' rel='icon' type='image/x-icon' />")
    (add-rest-initial viewport
      (html
       [:h2 "nostdal.org"]

       [:ul
        [:li "Email: " [:a {:href "mailto:larsnostdal@gmail.com"} "larsnostdal@gmail.com"]]
        [:li "Some source code at " [:a {:href "https://github.com/lnostdal/"} "GitHub"]]
        [:li [:a {:href "https://en.wikipedia.org/wiki/Clojure"} "Clojure"] " (on the Linux+Java platform)"]
        [:li [:a {:href "https://en.wikipedia.org/wiki/PostgreSQL"} "PostgreSQL"]]
        [:li [:a {:href "https://en.wikipedia.org/wiki/Linux"} "Linux"] " (since 1998)"]
        [:li "JavaScript (e.g. jQuery), HTML5, CSS, Nginx"]
        [:li [:a {:href "https://en.wikipedia.org/wiki/JSON"} "JSON"] ", HTTP (e.g. REST), XML"]
        [:li [:a {:href "https://en.wikipedia.org/wiki/Bootstrap_(front-end_framework)"} "Twitter Bootstrap"]
         ", " [:a {:href "https://en.wikipedia.org/wiki/Foundation_(framework)"} "Foundation (ZURB)"]
         " (nice for mobile devices)"]]

       [:p {:style (style {:font-family 'monospace})}
        "pub   4096R/7B281AED 2013-01-24" [:br]
        "Key fingerprint = 5029 6FDD 199C 2A69 898B  40B0 A08A C77A 7B28 1AED" [:br]
        "uid                  Lars Rune Nøstdal (PGP (RSA)) <larsnostdal@gmail.com>" [:br]
        "sub   4096R/F53DFC31 2013-01-24"]


       [:hr]
       [:span {:style (style {:font-family 'monospace})}
        "Darknet (" [:a {:href "https://geti2p.net/en/"} "I2P"] ") link: "
        [:a {:href "http://o6v2tmi2jc54b3rt5v6gd5qgie4yvvg4ocpyqtvy4rfxbgkecsjq.b32.i2p/nostdal.org"}
         "http://o6v2tmi2jc54b3rt5v6gd5qgie4yvvg4ocpyqtvy4rfxbgkecsjq.b32.i2p/nostdal.org"]
        [:br]
        "This page was generated by " [:a {:href "https://github.com/lnostdal/SymbolicWeb"} "SymbolicWeb"] ": "
        [:a {:href "https://en.wikipedia.org/wiki/Homoiconicity"} "data &#8596; code"] ". "]))
    viewport))



(defmulti mk-nostdal-org-viewport #(first %&))

(defmethod mk-nostdal-org-viewport :default [^String uri request session]
  (mk-Viewport request session (mk-bte :root-widget? true) :page-title "SW: :default"))

(defmethod mk-nostdal-org-viewport "/sw/nostdal.org" [^String uri request session]
  (homepage request session))



(defapp
  [::Nostdal-org (constantly true)]

  (fn [request session]
    ;; Can't set a cookie here as it will become global for all paths; redirect instead.
    (if (= (:uri request) "/sw/")
      (alter session assoc
             :rest-handler (fn [request session viewport]
                             (http-redirect-response "/sw/nostdal.org"))
             :one-shot? true)
      (alter session assoc
             :mk-viewport-fn (fn [request session]
                               (mk-nostdal-org-viewport (:uri request) request session))))
    session))
