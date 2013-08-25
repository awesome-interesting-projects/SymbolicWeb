(in-ns 'symbolicweb.core)

(declare add-response-chunk ref?)

(set! *print-length* 30)
(set! *print-level* 10)


(defn var-alter [^clojure.lang.Var var ^Fn fun & args]
  (var-set var (apply fun (var-get var) args)))


(defn ^String url-encode-component [^String s]
  ;; TODO: This is retarded and slow.
  (.replace (java.net.URLEncoder/encode s "UTF-8")
            "+"
            "%20"))


(defn ^String url-decode-component [^String s]
  (java.net.URLDecoder/decode s "UTF-8"))


(defn ^String mime-encode-rfc-2047 [^String s]
  (str "=?UTF-8?Q?"
       (-> (url-encode-component s)
           (str/replace "%20" "_")
           (str/replace "%" "="))
       "?="))


(defn expected-response-type [request]
  (let [accept-header (get (:headers request) "accept")]
    (cond
     (re-find #"text/javascript" accept-header) :javascript
     (re-find #"text/html" accept-header) :html
     (re-find #"text/plain" accept-header) :plain
     (re-find #"text/plugin-api" accept-header) :plugin-api
     true accept-header)))


(defn viewport? [o]
  (and (ref? o)
       (dosync (isa? (:type @o) ::Viewport))))


(declare viewport-of)
(defn session-of [widget]
  (when-let [viewport (viewport-of widget)]
    (:session @viewport)))


(defn alter-options [options fn & args]
  "> (alter-options (list :js-options {:close (with-js (alert \"closed!\"))}
                          :on-close (with-js (alert \"yep, really closed!\")))
                    update-in [:js-options] assoc :modal :true)
   (:js-options {:modal :true, :close \"alert(decodeURIComponent('closed%21'));\"}
    :on-close \"alert(decodeURIComponent('yep%2C%20really%20closed%21'));\")"
  (with-local-vars [lst (list)]
    (doseq [option (apply fn (apply hash-map options) args)]
      (var-set lst (conj (var-get lst) (val option) (key option))))
    (var-get lst)))


(defmacro typecase [e & clauses]
  ;; (str 'clojure.lang.PersistentVector)
  ;; (case (.getName (type []))
  ;;   "clojure.lang.PersistentVector" (println "test"))
  ;;
  ;; <clgv> lnostdal_: another way is to use cond and instance? see ##(doc instance?)
  ;; <lazybot> ⇒ "([c x]); Evaluates x and tests if it is an instance of the class c. Returns true or false"
  )


(defn sha ^String [^String input-str]
  (let [md (java.security.MessageDigest/getInstance "SHA-512")]
    (. md update (.getBytes input-str))
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))


(defn ensure-vector [x]
  (if (vector? x)
    x
    (vector x)))


(defmacro with-all-viewports [& body]
  "Handy when testing things in the REPL.
SESSION and VIEWPORT are bound within BODY."
  `(doseq [~'session (vals @-sessions-)]
     (doseq [~'viewport (vals @(:viewports @~'session))]
       ~@body)))


(defmacro with-session-viewports [session & body]
  "Run BODY in context of all Viewports of SESSION..
SESSION and VIEWPORT are bound within BODY."
  `(let [~'session ~session]
     (doseq [~'viewport (vals @(:viewports @~'session))]
       ~@body)))


(defmacro with-user-viewports [user-model & body]
  "Run BODY in context of all Viewports in all Sessions of USER-MODEL (UserModelBase).
SESSION and VIEWPORT are bound within BODY."
  `(let [user-model# ~user-model]
     (doseq [session# (dosync @(:sessions @user-model#))]
       (with-session-viewports session#
         ~@body))))


(defn get-widget [^String id ^Ref viewport]
  (get (:widgets @viewport) id))


(defn ensure-model [obj]
  (assert (ref? obj))
  obj)


(defn url-encode-wrap ^String [^String s]
  (str "decodeURIComponent('" (url-encode-component s) "')"))


(defn agent? [x]
  (= clojure.lang.Agent (type x)))


(defn default-parse-callback-data-handler [request widget callback-data]
  (mapcat (fn [key]
            (list key (get (:params request) (str key))))
          (keys callback-data)))


(defn map-to-js-options [opts]
  (with-out-str
    (doseq [opt opts]
      (print (str (name (key opt)) ": "
                  (with (val opt)
                    (if (keyword? it)
                      (name it)
                      it))
                  ", ")))))


(defn defapp [[name fit-fn & args] session-constructor-fn]
  (swap! -session-types-
         #(assoc % name (apply hash-map
                               :name name
                               :fit-fn fit-fn
                               :cookie-name "_sw_s"
                               :session-constructor-fn session-constructor-fn
                               args))))


(let [id-generator-value (atom 0)]
  (defn ^Long generate-uid []
    "Generates an unique ID; non-universal or only pr. server instance based."
    (swap! id-generator-value inc)))


(defn generate-uuid ^String []
  "Generates an universal unique ID (UUID).
Returns a String."
  (.toString (java.util.UUID/randomUUID)))


(defn touch [^Ref obj]
  (reset! ^Atom (:last-activity-time @obj)
          (System/currentTimeMillis)))


 (defn script-src [^String src]
  [:script {:type "text/javascript" :src src}])


(defn link-css [^String href]
  [:link {:rel "stylesheet" :type "text/css"
          :href href}])



(defn ^String set-document-cookie [& {:keys [path domain? name value permanent?]
                                      :or {domain? true
                                           path "\" + window.location.pathname + \""
                                           name "name"
                                           value "value"
                                           permanent? true}}]
  (str "document.cookie = \""
       name "=" (if value value "") "; "
       (when domain?
         (if (= domain? true)
           "domain=\" + window.location.hostname + \"; "
           (str "domain=" domain? "; ")))
       (if value
         (if-not permanent?
           ""
           (str "expires=\""
                " + (function(){ var date = new Date(); date.setFullYear(date.getFullYear() + 42); return date.toUTCString(); })()"
                " + \"; "))
         "expires=Fri, 27 Jul 2001 02:47:11 UTC; ")
       "path=" path "; "
       "secure=true;"
       "\";\n"))



(defn ^String set-session-cookie [value permanent?]
  (set-document-cookie :name -session-cookie-name- :value value :domain? false :permanent? permanent?))



(defn remove-session [^Ref session]
  (let [session @session]
    (swap! -sessions- #(dissoc % (:id session)))))


(defn http-js-response [body]
  {:status 200
   :headers {"Content-Type" "text/javascript; charset=UTF-8"}
   :body body})


(defn http-html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=UTF-8"}
   :body body})


(defn http-text-response [body]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body body})


(defn http-replace-response [location]
  {:status 200
   :headers {"Content-Type" "text/html; charset=UTF-8"}
   :body (str "<script> window.location.replace(" (url-encode-wrap location) "); </script>")})


(defn http-redirect-response [location]
  {:status 200
   :headers {"Content-Type" "text/html; charset=UTF-8"}
   :body (str "<script> window.location = " (url-encode-wrap location) "; </script>")})


(defn reload-page
  ([viewport rel-url]
     (add-response-chunk (str "window.location = " (url-encode-wrap rel-url) ";")
                         viewport))
  ([viewport]
     ;; TODO: I guess we need three ways of reloading now.
     ;;"window.location.reload(false);"
     ;; http://blog.nostdal.org/2011/12/reloading-or-refreshing-web-page-really.html
     (add-response-chunk "window.location.href = window.location.href;"
                         viewport)))


(defn replace-page [rel-url viewport]
  (add-response-chunk (str "window.location.replace(" (url-encode-wrap rel-url) ");")
                      viewport))


(defn clear-session [session]
  ;; TODO: Do this on the server end instead or also?
  (with-session-viewports session
    (add-response-chunk (set-session-cookie nil)
                        viewport)
    (add-response-chunk "window.location.href = window.location.href;"
                        viewport)))



#_(defn clear-all-sessions []
  ;; TODO: Do this on the server end instead or also.
  (doseq [id_application @-applications-]
    (binding [*application* (val id_application)]
      (doseq [id_viewport (:viewports @*application*)]
        (binding [*viewport* (val id_viewport)]
          (clear-session *application*))))))


(defn is-url?
  ([url-path uri]
     (= (subs uri 0 (min (count url-path) (count uri)))
        url-path)))


(defn alert
  ([msg widget]
     (add-response-chunk (str "alert(" (url-encode-wrap msg) ");")
                         widget)))


(declare spget)
(defn ^String sw-js-base-bootstrap [^Ref session ^Ref viewport]
  (str "var sw_cookie_name = '" -session-cookie-name- "';\n"
       (set-session-cookie (:uuid @session) (= "permanent" @(spget session :session-type)))
       "var _sw_viewport_id = '" (:id @viewport) "';\n"
       "var _sw_comet_timeout_ms = " -comet-timeout- ";\n"))



(defn pred-with-limit [pred limit]
  "Returns a new predicate based on PRED which only \"lasts\" LIMIT number of times."
  (let [limit (atom limit)]
    (fn [elt]
      (if-not (pred elt)
        true
        (if (zero? @limit)
          true
          (do
            (reset! limit (dec @limit))
            false))))))


#_(defn with-future* [timeout-ms body-fn]
  "Executes BODY-FN in a future with a timeout designated by TIMEOUT-MS for execution; i.e. not only for deref."
  (let [the-future (future (with-errors-logged (body-fn)))]
    (future
      (with-errors-logged
        (let [result (deref the-future timeout-ms ::with-future-timeout-event)]
          (if (not= result ::with-future-timeout-event)
            result
            (future-cancel the-future)))))
    nil))


#_(defmacro with-future [timeout-ms & body]
  "Executes BODY in a future with a timeout designated by TIMEOUT-MS for execution; i.e. not only for deref."
  `(with-future* ~timeout-ms (fn [] ~@body)))



;;; WITH-SW-IO
;;;;;;;;;;;;;;

;; TODO: Yes, this is all quite horrible. The binding propagation thing in Clojure is not a good thing IMHO.


(def -sw-io-agent-error-handler-
  (fn [the-agent exception]
    (try
      (flush)
      (println "-SW-IO-AGENT-ERROR-HANDLER- (" the-agent "), thrown:") (flush)
      (clojure.stacktrace/print-stack-trace exception 1000) (flush)
      (catch Throwable inner-exception
        (println "-SW-IO-AGENT-ERROR-HANDLER-: Dodge überfail... :(") (flush)
        (Thread/sleep 1000))))) ;; Make sure we aren't flooded in case some loop gets stuck.



(defn mk-sw-agent [m binding-whitelist]
  (merge {:clj-agent (agent :initial-state)
          :binding-whitelist (into (keys (get-thread-bindings))
                                   binding-whitelist)
          :executor clojure.lang.Agent/soloExecutor} ;; This is SEND-OFF, and pooledExecutor is SEND.
         m))



(def -sw-io-agent- (mk-sw-agent {} nil)) ;; Generic fallback Agent. TODO: Perhaps a bad idea?



(defn with-sw-agent* [m ^Fn body-fn]
  (let [sw-agent (if m (merge -sw-io-agent- m) -sw-io-agent-)
        ^clojure.lang.Agent clj-agent (:clj-agent sw-agent)]
    (.dispatch clj-agent
               (let [bnds (get-thread-bindings)]
                 (fn [& _]
                   (with-bindings (merge (select-keys bnds (:binding-whitelist sw-agent))
                                         {#'clojure.core/*agent* clj-agent})
                     (try
                       (body-fn) (flush)
                       (catch Throwable e
                         (try
                           (if-let [^Fn f (:exception-handler-fn sw-agent)]
                             (f sw-agent e)
                             (-sw-io-agent-error-handler- sw-agent e))
                           (catch Throwable ne
                             (println "WITH-SW-IO*: Exception while handling exception!") (flush)
                             (println "\nOriginal exception:") (flush)
                             (clojure.stacktrace/print-stack-trace e 1000)
                             (println "\nNested exception:") (flush)
                             (clojure.stacktrace/print-stack-trace ne 1000) (flush)
                             (Thread/sleep 2000)))))))) ;; Dodge potential flood from a loop or similar.
               nil
               (:executor sw-agent))))



(defmacro with-sw-agent [m & body]
  `(with-sw-agent* ~m (fn [] ~@body)))
