(in-ns 'symbolicweb.core)



(defn %mk-SessionTable []
  (with-db-conn
    (jdbc/do-commands
"
CREATE TABLE sessions (
    id text NOT NULL,
    created timestamp without time zone NOT NULL,
    touched timestamp without time zone NOT NULL,
    data text DEFAULT '{}'::text NOT NULL,
    application text
);
")))



(defn session-model-clj-to-db-transformer [m]
  "SW --> DB"
  (-> m
      ((fn [m]
         (case (:key m)
           (:type :logged-in? :last-activity-time :viewports :mk-viewport-fn
            :request-handler :rest-handler :ajax-handler :aux-handler
            :one-shot?)
           (assoc m
             :key nil)

           :session-type
           (assoc m
             :key :application
             :value (name (:name (:value m))))

           :user-model
           (assoc m
             :key :user-ref
             :value (when-let [user-model @(:value m)]
                      @(:id @user-model)))

           m)))
      (db-default-clj-to-db-transformer)))



(defn session-model-db-to-clj-transformer [m]
  "DB --> SW"
  (-> m
      (db-default-db-to-clj-transformer)
      ((fn [m]
         (case (:key m)
           :user-ref
           (assoc m
             :key :user-model
             :value (when (:value m) (db-get (:value m) "users")))

           m)))))



(defn mk-Session [& args]
  (let [session (ref (apply assoc {}
                            :id (vm nil)
                            :type ::Session
                            :uuid nil
                            :user-model (vm nil)
                            :last-activity-time (atom (System/currentTimeMillis))
                            :viewports (ref {})
                            :mk-viewport-fn (fn [request ^Ref session]
                                              (throw (Exception. "mk-Session: No :MK-VIEWPORT-FN given.")))
                            :one-shot? false

                            :created (datetime-to-sql-timestamp (time/now))
                            :touched (vm (datetime-to-sql-timestamp (time/now)))

                            :request-handler #'default-request-handler
                            :rest-handler #'default-rest-handler
                            :ajax-handler #'default-ajax-handler
                            :aux-handler #'default-aux-handler
                            args))]
    session))



(swap! -db-cache-constructors- assoc "sessions"
       #(mk-DBCache "sessions"
                    (fn [db-cache id] (mk-Session))
                    identity
                    #'session-model-clj-to-db-transformer
                    #'session-model-db-to-clj-transformer))
(db-reset-cache "sessions")



(defn session-get [^Ref session ^Keyword k]
  (db-json-get (:json-store @session) k))



(defn session-del [^Ref session ^Keyword k]
  (vm-alter (:json-store @session)
            dissoc k))



(defn find-session-constructor [request]
  (loop [session-types @-session-types-]
    (when-first [session-type session-types]
      (let [session-type (val session-type)]
        (if ((:fit-fn session-type) request)
          session-type
          (recur (next session-types)))))))



(defn search-engine? [request]
  (let [^String user-agent (get (:headers request) "user-agent")]
    (not (or (neg? (.indexOf user-agent "bot"))
             (neg? (.indexOf user-agent "Mediapartners-Google"))
             (neg? (.indexOf user-agent "ia_archiver"))
             ))))



(declare json-parse)
(defn find-or-create-session [request]
  (let [cookie-value (:value (get (:cookies request) -session-cookie-name-))]
    (if-let [session (get (ensure -sessions-) cookie-value)]
      session
      (if-let [session-type (find-session-constructor request)]
        (let [one-shot?
              (or (with (get (:query-params request) "_sw_session_one_shot_p")
                    (if (nil? it)
                      false
                      (json-parse it)))
                  (search-engine? request))

              session-skeleton
              (or (and cookie-value
                       (when-let [res (first (db-pstmt "SELECT id FROM sessions WHERE uuid = ? LIMIT 1;" cookie-value))]
                         (with1 (db-get (:id res) "sessions")
                           (vm-set (:touched @it) (datetime-to-sql-timestamp (time/now))))))
                  (with1 (mk-Session :uuid (generate-uuid) :session-type session-type)
                    (when-not one-shot?
                      (db-put it "sessions"))))]
          (alter session-skeleton assoc
                 :session-type session-type
                 :one-shot? one-shot?)
          (when-not one-shot?
            (alter session-skeleton assoc
                 :json-store (db-json-store-get "sessions" @(:id @session-skeleton) :data))
            (alter -sessions- assoc (:uuid @session-skeleton) session-skeleton)
            (vm-alter -num-sessions-model- + 1))
          ((:session-constructor-fn session-type) session-skeleton))
        (do
          (log "FIND-OR-CREATE-SESSION: 404 NOT FOUND:" request)
          (mk-Session :uuid cookie-value
                      :rest-handler not-found-page-handler
                      :mk-viewport-fn (fn [request session]
                                        (mk-Viewport request session (mk-bte :root-widget? true))) ;; Dummy.
                      :one-shot? true))))))



(defn undefapp [name]
  (swap! -session-types- #(dissoc % name)))
