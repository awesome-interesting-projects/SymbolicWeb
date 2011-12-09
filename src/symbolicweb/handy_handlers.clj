(in-ns 'symbolicweb.core)

(declare make-Application)


(defn default-aux-handler []
  "Attempt to handle auxiliary callbacks or events; AJAX requests that do not follow the SW protocol. These might come from 3rd
party code or plugins or similar running on the browser end.
Returns TRUE if the event was handled or FALSE if no callback was found for the event."
  (assert false "TODO: DEFAULT-AUX-HANDLER")
  (with-local-vars [handled? false]
    (loop [aux-callbacks (:aux-callbacks @*viewport*)]
      (when-first [aux-callback aux-callbacks]
        (let [aux-callback (val aux-callback)]
          (if ((:fit-fn aux-callback))
            (do
              ((:handler-fn aux-callback))
              (var-set handled? true))
            (recur (next aux-callbacks))))))
    (var-get handled?)))


(defn handle-out-channel-request [channel request]
  "Output channel."
  (letfn [(do-it []
            (let [viewport-m @*viewport*
                  response-str (:response-str viewport-m)
                  response-promise (:response-promise viewport-m)]
              (enqueue channel
                       {:status 200
                        :headers {"Content-Type" "text/javascript; charset=UTF-8"
                                  "Server" "SymbolicWeb"}
                        :body (with1 (str @response-str "_sw_comet_response = true;")
                                (reset! response-str ""))})))]
    (locking *viewport*
      (let [viewport-m @*viewport*
            response-sched-fn (:response-sched-fn viewport-m)
            response-str (:response-str viewport-m)]
        (if (pos? (count @response-str))
          (do-it)
          (let [thread-bindings (get-thread-bindings)]
            (assert (not @response-sched-fn))
            (reset! response-sched-fn
                    (at (+ (now) -comet-timeout-)
                        #(with-bindings thread-bindings
                           (locking *viewport*
                             (reset! response-sched-fn nil)
                             (do-it)))))))))))


(defn handle-in-channel-request []
  "Input channel."
  (let [query-params (:query-params *request*)
        widget-id (get query-params "_sw_widget-id")
        callback-id (get query-params "_sw_callback-id")
        widget (get (:widgets @*viewport*) widget-id)
        callback (get (:callbacks @widget) callback-id)
        [callback-fn callback-data] callback]
    (dosync
     (binding [*in-channel-request?* ""]
       (apply callback-fn ((:parse-callback-data-handler @widget) widget callback-data))
       {:status 200
        :headers {"Content-Type" "text/javascript; charset=UTF-8"
                  "Server" "SymbolicWeb"}
        :body *in-channel-request?*}))))



(defn default-ajax-handler []
  (if-let [sw-request-type-str (get (:query-params *request*) "_sw_request_type")]
    (case sw-request-type-str
      "comet" ((wrap-aleph-handler #'handle-out-channel-request) *request*)
      "ajax"  (handle-in-channel-request)
      (throw (Exception. (str "SymbolicWeb: Unknown _sw_request_type \"" sw-request-type-str "\" given."))))
    (dosync
     ((:aux-handler @*application*)))))


(declare clear-session-page-handler)
(defn default-request-handler []
  "Default top-level request handler for both REST and AJAX/Comet type requests."
  (if (get (:query-params *request*) "_sw_request_type") ;; sw-ajax.js adds this to our AJAX requests.
    ;; AJAX.
    (if (= clear-session-page-handler (:rest-handler @*application*))
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=UTF-8"
                 "Server" "SymbolicWeb"}
       :body (str (set-default-session-cookie nil)
                  "window.location.reload();")}
      (if-let [viewport (get (:viewports @*application*)
                             (get (:query-params *request*) "_sw_viewport_id"))]
        (binding [*viewport* viewport]
          (dosync (touch *viewport*))
          ((:ajax-handler @*application*)))
        {:status 200
         :headers {"Content-Type" "text/javascript; charset=UTF-8"
                   "Server" "SymbolicWeb"}
         :body
         (with-js (clear-session))}))
    ;; REST.
    (binding [*viewport* (when (:session? @*application*)
                           ((:make-viewport-fn @*application*)))]
      (dosync
       ((:reload-handler @*application*))
       ((:rest-handler @*application*))))))


(defn clear-session-page-handler []
  "Clears the session; removes client side cookies and reloads the page."
  (let [accept-header (expected-response-type)]
    (case accept-header
      :javascript
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=UTF-8"
                 "Server" "SymbolicWeb"}
       :body
       (str ;; Clear session cookie and reload page.
        (set-default-session-cookie nil)
        ;; TODO: This can be removed later; it's used to clear out any old cookies from before TM joined us.
        (set-document-cookie :name "_sw_application_id" :value nil :path "/free-or-deal/sw" :domain? ".dev.kitch.no")
        "window.location.reload();")}

      :html
      {:status 200
       :headers {"Content-Type" "text/html; charset=UTF-8"
                 "Server" "SymbolicWeb"}
       :body
       (html
        (doctype :xhtml-strict)
        (xhtml-tag
         "en"
         [:head
          [:title "Reloading page..."]
          [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
          ;; Clear session cookie and reload page.
          [:script {:type "text/javascript"}
           (set-default-session-cookie nil)]]
         [:body {:onload "window.location.reload();"}
          [:p "Reloading page..."]]))}

      :plugin-api
      {:status 404
       :headers {"Content-Type" "text/plain; charset=UTF-8"
                 "Server" "SymbolicWeb"}
       :body "This session has timed out."}

      (println "CLEAR-SESSION-PAGE-HANDLER: Unknown HTTP ACCEPT header value:" accept-header))))


(defn default-rest-handler []
  {:status  200
   :headers {"Content-Type"  "text/html; charset=UTF-8"
             "Server" "SymbolicWeb"
             "Expires"       "Mon, 26 Jul 1997 05:00:00 GMT"
             "Cache-Control" "no-store, no-cache, must-revalidate, post-check=0, pre-check=0"
             "Pragma"        "no-cache"}
   :body
   (html
    (doctype :xhtml-strict)
    (xhtml-tag
     "en"
     [:head
      [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]
      ;; Do want; http://blog.chromium.org/2009/09/introducing-google-chrome-frame.html
      [:meta {:http-equiv "X-UA-Compatible" :content "chrome=1"}]
      [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]
      [:title (:html-title @*application*)]
      ;; TODO: #sw-root doesn't exist anymore; check ID of :ROOT-ELEMENT!
      [:style {:type "text/css"} "
* {
  -webkit-box-sizing: border-box; /* Safari/Chrome, other WebKit */
  -moz-box-sizing: border-box;    /* Firefox, other Gecko */
  box-sizing: border-box;         /* Opera/IE 8+ */
}
html, body, #sw-root {
  font-family: sans-serif;
  position: absolute;
  width: 100%; height: 100%;
  margin: 0; padding: 0; border: 0;
}
.sw-hide {
  display: none !important;
}"]
      (sw-css-bootstrap)
      (script-src "/js/common/jquery-1.6.4.min.js")
      (script-src "/js/common/sw/jquery.sha256/jquery.sha256.js")
      (with-out-str
        (doseq [head-element (:head-elements @*application*)]
          (println head-element)))
      (sw-js-bootstrap)]

     [:body
      (render-html (:root-element @*viewport*) (:root-element @*viewport*))]))})


(defn not-found-page-handler []
  {:status 404
   :headers {"Content-Type" "text/html; charset=UTF-8"
             "Server" "SymbolicWeb"}
   :body
   (html
    (doctype :xhtml-strict)
    (xhtml-tag
     "en"
     [:head
      [:title "[SymbolicWeb] HTTP 404: Not Found"]
      [:meta {:http-equiv "Content-Type" :content "text/html; charset=UTF-8"}]]

     [:body
      [:h1 "HTTP 404: Not Found"]
      [:p "Going " [:a {:href "javascript:history.go(-1);"} "back"] " might help."]]))})


(defn simple-aux-handler [fn-to-wrap]
  (fn []
    (binding [*in-channel-request?* ""]
      (fn-to-wrap)
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=UTF-8"
                 "Server" "SymbolicWeb"}
       :body *in-channel-request?*})))



(defapp
  [::EmptyPage #(is-url? "/empty-page/sw") "/empty-page/sw"]
  (fn [app-type-data]
    (make-Application)))
