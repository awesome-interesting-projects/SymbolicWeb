(in-ns 'symbolicweb.core)


(defn mk-db-pool [spec]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass (:classname spec))
               (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
               (.setUser (:user spec))
               (.setPassword (:password spec))
               ;; Expire excess connections after 30 minutes of inactivity:
               (.setMaxIdleTimeExcessConnections (* 30 60))
               ;; Expire connections after 3 hours of inactivity:
               (.setMaxIdleTime (* 3 60 60)))]
    (delay {:datasource cpds})))


(defonce -pooled-db-spec-
  (atom (mk-db-pool (let [db-host  "localhost"
                          db-port  5432
                          db-name  "temp"
                          user     "temp"
                          password "temp"]
                      {:classname "org.postgresql.Driver"
                       :subprotocol "postgresql"
                       :subname (str "//" db-host ":" db-port "/" db-name)
                       :user user
                       :password password}))))



(defn finalize-with-sw-ctx [ctx]
  (doseq [f ctx]
    (f)))

(def ^:dynamic *with-sw-ctx*)

(defmacro with-sw-ctx [& body]
  `(do
     (assert (not (thread-bound? #'*with-sw-ctx*)))
     (binding [*with-sw-ctx* (atom [])]
       (with1 ~@body
         (finalize-with-sw-ctx @*with-sw-ctx*)))))

(defn sw-ctx-add-fn [f]
  (swap! *with-sw-ctx* conj f))

(defmacro in-sw-ctx [& body]
  `(sw-ctx-add-fn (fn [] ~@body)))


#_(locking -pooled-db-spec-
    (with-sw-ctx
      (transaction
       (body-fn))))


(def ^:dynamic *pending-prepared-transaction?* false)

(defn %with-sw-connection [body-fn]
  (io!
   (assert (not *pending-prepared-transaction?*))
   (try
     (with-connection @@-pooled-db-spec- ;; DEREF Atom, then Delay.
       (body-fn))
     (catch java.sql.SQLException e
       (if (= "40001" (. e getSQLState))
         (do
           (println "%WITH-SW-CONNECTION: Serialization conflict; retrying!")
           (%with-sw-connection body-fn))
         (do
           (print-sql-exception-chain e)
           (throw e)))))))

(defmacro with-sw-connection [& body]
  `(%with-sw-connection (fn [] ~@body)))


(defn with-sw-db [body-fn]
  ;; TODO: Check whether we're already in a WITH-SW-DB context and just call BODY-FN directly here if we are! What do we do about
  ;; its AFTER-FN though? Perhaps we should simply disallow nesting of WITH-SW-DB forms.
  (with-sw-connection
    ;; The BINDING here is sort of a hack to ensure that java.jdbc's UPDATE-VALUES etc. type functions doesn't create
    ;; inner transactions which will commit even though we'd want them to roll back here in WITH-SW-DB.
    (binding [clojure.java.jdbc.internal/*db* (update-in clojure.java.jdbc.internal/*db* [:level] inc)]
      (let [id-str (str (generate-uid))
            conn (:connection clojure.java.jdbc.internal/*db*)
            stmt (.createStatement conn)]
        (with-local-vars [after-fn nil
                          commit-inner-transaction? false
                          commit-prepared-transaction? false]
          (try
            (.setTransactionIsolation conn java.sql.Connection/TRANSACTION_SERIALIZABLE)
            (.setAutoCommit conn false) ;; Start transaction.
            (let [result (body-fn (fn [callback] (var-set after-fn callback)))]
              (.execute stmt (str "PREPARE TRANSACTION '" id-str "';"))
              (.commit conn) (.setAutoCommit conn true) ;; Semi-end transaction.
              (var-set commit-inner-transaction? true)
              (when-let [after-fn (var-get after-fn)]
                (binding [clojure.java.jdbc.internal/*db* nil ;; Cancel out current DB connection while we do this..
                          *pending-prepared-transaction?* true] ;; ..and make sure no further connections can be made here.
                  (after-fn id-str)))
              (var-set commit-prepared-transaction? true)
              result)
            (finally
             (if (var-get commit-inner-transaction?)
               (if (var-get commit-prepared-transaction?)
                 (do
                   (.execute stmt (str "COMMIT PREPARED '" id-str "';"))
                   ;; TODO: At this point we can commit the *SWSYNC* "transaction".
                   )
                 (.execute stmt (str "ROLLBACK PREPARED '" id-str "';")))
               (do (.rollback conn) (.setAutoCommit conn true)))
             (.close stmt))))))))

(defn db-stmt [sql-str]
  (let [stmt (.createStatement (:connection clojure.java.jdbc.internal/*db*))]
    (.execute stmt sql-str)
    (.close stmt)))


(defn db-delete-prepared-transactions []
  (with-sw-connection
    (with-query-results res ["SELECT gid FROM pg_prepared_xacts;"]
      (doseq [res res]
        (println "deleting prepared transaction:" (:gid res))
        (db-stmt (str "ROLLBACK PREPARED '" (:gid res) "';"))))))


(reset! -pooled-db-spec-
        (mk-db-pool (let [db-host  "localhost"
                          db-port  5432
                          db-name  "temp"
                          user     "temp"
                          password "xrEj7MY4e0"]
                      {:classname "org.postgresql.Driver"
                       :subprotocol "postgresql"
                       :subname (str "//" db-host ":" db-port "/" db-name)
                       :user user
                       :password password})))



;; To test out serialization conflict:
(defn test-serialization [do-after]
  (db-delete-prepared-transactions) ;; NOTE: While developing.
  (let [local (ref [])
        f (future
            (with-sw-db
              (fn [after-transaction]
                (with-query-results res ["SELECT * FROM test WHERE id = ?;" 78]
                  (println "inner-transaction #1 before:" res)
                  (update-values :test ["id = ?" 78] {:value (inc (Integer/parseInt (:value (first res))))}))
                (with-query-results res ["SELECT * FROM test WHERE id = ?;" 78]
                  (println "inner-transaction #1 after:" res))

                (dosync (alter local conj "inner-transaction #1"))

                (when do-after
                  (after-transaction (fn [tid]
                                       (println "after-transaction #1: begin")
                                       (dosync (alter local conj "after-transaction #1"))
                                       (Thread/sleep 1000)
                                       (println "after-transaction #1: end")))))))]

    (Thread/sleep 500) ;; To make sure the first ts has gotten to its call to Thread/sleep.
    (with-sw-db
      (fn [after-transaction]
        (with-query-results res ["SELECT * FROM test WHERE id = ?;" 78]
          (println "inner-transaction #2 before:" res)
          (update-values :test ["id = ?" 78] {:value (inc (Integer/parseInt (:value (first res))))}))
        (with-query-results res ["SELECT * FROM test WHERE id = ?;" 78]
          (println "inner-transaction #2 after:" res))
        (dosync (alter local conj "inner-transaction #2"))
        (when do-after
          (after-transaction (fn [tid]
                               (dosync
                                (println "after-transaction #2: begin")
                                (alter local conj "after-transaction #2")
                                (println "after-transaction #2: begin")))))))
    @f
    @local))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Persistance stuff comes here.


(defrecord DBCache
    [db-handle-input-fn
     db-handle-output-fn
     agent
     ^String table-name
     constructor-fn ;; Function called on cache miss to construct the initial skeleton for the data from the DB to fill up.
     ^ReferenceMap cache-data]) ;; http://commons.apache.org/collections/api/org/apache/commons/collections/ReferenceMap.html


(defn default-db-handle-input [db-cache object input-key input-value]
  "SW --> DB.
Swaps - with _ for INPUT-KEY and passes INPUT-VALUE through as is."
  (when input-key
    [(keyword (str/replace (name input-key) #"-" "_"))
     input-value]))

(defn db-handle-input [db-cache object input-key input-value]
  "SW --> DB.
Returns two values in form of a vector [TRANSLATED-INPUT-KEY TRANSLATED-INPUT-VALUE] or returns NIL if the field in question,
represented by INPUT-KEY, is not to be stored in the DB."
  (if-let [f (. db-cache db-handle-input-fn)]
    (f db-cache object input-key input-value)
    (default-db-handle-input object input-key input-value)))

(defn default-db-handle-output [db-cache object output-key output-value]
  "DB --> SW.
Swaps _ with - for OUTPUT-KEY and passes OUTPUT-VALUE through as is."
  (when output-key
    [(keyword (str/replace (name output-key) #"_" "-"))
     output-value]))

(defn db-handle-output [db-cache object output-key output-value]
  "DB --> SW.
Returns two values in form of a vector [TRANSLATED-OUTPUT-KEY TRANSLATED-OUTPUT-VALUE] or returns NIL if the field in question,
represented by OUTPUT-KEY, is not to be fetched from the DB."
  (if-let [f (. db-cache db-handle-output-fn)]
    (f db-cache object output-key output-value)
    (default-db-handle-output db-cache object output-key output-value)))


(defn db-ensure-persistent-field [db-cache object ^Long id ^clojure.lang.Keyword key ^ValueModel value-model]
  "Setup reactive SQL UPDATEs for VALUE-MODEL."
  (mk-view value-model nil
           (fn [value-model old-value new-value]
             (when-not (= old-value new-value) ;; TODO: This should probably be generalized and handled before the notification
               (let [[input-key input-value] (db-handle-input db-cache object key new-value)] ;; is even sent to callbacks.
                 (when input-key
                   (update-values (. db-cache table-name) ["id=?" id]
                                  {(as-quoted-identifier \" input-key) input-value})))))
           :trigger-initial-update? false))


(defn db-backend-get [db-cache ^Long id ^clojure.lang.Ref obj]
  "SQL SELECT. This will mutate fields in OBJ or add missing fields to OBJ.
Returns OBJ, or NIL if no entry with id ID was found in (:table-name DB-CACHE)."
  (with-query-results res [(str "SELECT * FROM " (as-quoted-identifier \" (. db-cache table-name)) " WHERE id = ? LIMIT 1;") id]
    (when-let [res (first res)]
      (dosync
       (let [obj-m (ensure obj)]
         (doseq [key_val res]
           (let [[output-key output-value] (db-handle-output db-cache obj (key key_val) (val key_val))]
             (when output-key
               (if (output-key obj-m)
                 (do
                   (vm-set (output-key obj-m) output-value)
                   (db-ensure-persistent-field db-cache obj (:id res) output-key (output-key obj-m)))
                 (let [vm-output-value (vm output-value)]
                   (ref-set obj (assoc (ensure obj) output-key vm-output-value))
                   (db-ensure-persistent-field db-cache obj (:id res) output-key vm-output-value)))))))
       obj))))


(declare db-cache-put)
(defn db-backend-put
  "SQL INSERT of OBJ whos keys and values are translated via DB-HANDLE-INPUT. This will also add OBJ to DB-CACHE unless
UPDATE-CACHE? is given a FALSE value."
  ([obj db-cache] (db-backend-put obj db-cache true))
  ([obj db-cache update-cache?]
     (let [record-data
           (dosync
            (assert (or (not (dosync (:id (ensure obj))))
                        (not (dosync @(:id (ensure obj))))))
            (with-local-vars [record-data {}]
              (doseq [key_val (ensure obj)]
                (when (isa? (type (val key_val)) ValueModel) ;; TODO: Possible magic check. TODO: Foreign keys; ContainerModel.
                  (let [[input-key input-value] (db-handle-input db-cache obj (key key_val) @(val key_val))]
                    (when input-key
                      (var-set record-data (assoc (var-get record-data)
                                             input-key input-value))))))
              (var-get record-data)))
           res (insert-record (. db-cache table-name) record-data)] ;; SQL INSERT.
       (when update-cache?
         (db-cache-put db-cache (:id res) obj))
       (dosync
        (let [obj-m (ensure obj)]
          (doseq [key_val res]
            (let [[output-key output-value] (db-handle-output db-cache obj (key key_val) (val key_val))]
              (when output-key
                ;; Update and add fields in OBJ where needed based on result of SQL INSERT operation.
                (if (= ::not-found (get obj-m output-key ::not-found))
                  (let [vm-output-value (vm output-value)]
                    (ref-set obj (assoc (ensure obj) output-key vm-output-value)) ;; Add.
                    (db-ensure-persistent-field db-cache obj (:id res)
                                                output-key vm-output-value))
                  (do
                    (vm-set (output-key obj-m) output-value) ;; Update.
                    (db-ensure-persistent-field db-cache obj (:id res)
                                                output-key (output-key obj-m))))))))))
     obj))



(defn mk-db-cache [table-name constructor-fn db-handle-input-fn db-handle-output-fn]
  (DBCache.
   (if db-handle-input-fn db-handle-input-fn default-db-handle-input)
   (if db-handle-output-fn db-handle-output-fn default-db-handle-output)
   (agent :db-cache-agent)
   table-name
   constructor-fn
   (ReferenceMap. ReferenceMap/HARD ReferenceMap/SOFT)))

(defonce -db-cache-constructors- (atom {})) ;; table-name -> fn
(defonce -db-caches- ;; table-name -> ReferenceMap
  (ReferenceMap. ReferenceMap/HARD ReferenceMap/SOFT))

(defn db-get-cache [table-name]
  ;; A cache for TABLE-NAME must be found.
  {:post [(if % true (do (println "DB-GET-CACHE: No cache found for" table-name) false))]}
  (if-let [db-cache (. -db-caches- get table-name)]
    db-cache
    (locking -db-caches-
      (if-let [db-cache (. -db-caches- get table-name)]
        db-cache
        (when-let [db-cache (get @-db-cache-constructors- table-name)]
          (let [db-cache (db-cache)]
            (. -db-caches- put table-name db-cache)
            db-cache))))))

(defn db-reset-cache [table-name]
  (locking -db-caches-
    (. -db-caches- remove table-name)))

(defn reset-db-cache [table-name]
  (db-reset-cache table-name))


(defn db-cache-put [db-cache ^Long id obj]
  "If ID is NIL, store OBJ in DB then store association between the resulting id and OBJ in DB-CACHE.
If ID is non-NIL, store association between ID and OBJ in DB-CACHE.
Fails (via assert) if an object with the same id already exists in DB-CACHE."
  (let [id (Long. id)] ;; Because (. (int 261) equals 261) => false
    (locking db-cache
      (let [cache-data (. db-cache cache-data)]
        (assert (not (. cache-data containsKey id)) "DB-CACHE-PUT: Ups. This shouldn't happen.")
        (. cache-data put id obj)))))


(defn db-cache-get [db-cache ^Long id construction-fn]
  "Get object based on ID from DB-CACHE or backend (via CONSTRUCTOR-FN in DB-CACHE).

Assuming DB-CACHE-GET is the only function used to fetch objects from the back-end (DB), this will do the needed locking to ensure
that only one object with id ID exists in the cache and the system at any point in time. It'll fetch from the DB using
:CONSTRUCTOR-FN from DB-CACHE."
  (let [id (Long. id)] ;; Because (. (int 261) equals 261) => false
    (if-let [cache-entry (. (. db-cache cache-data) get id)]
      cache-entry
      (if-let [cache-entry (locking db-cache (. (. db-cache cache-data) get id))] ;; Check cache again while within lock.
        cache-entry
        (if-let [new-obj (db-backend-get db-cache id ((. db-cache constructor-fn) db-cache id))]
          ;; Check cache yet again while within lock; also possibly adding NEW-OBJ to it still within lock.
          (locking db-cache
            (if-let [cache-entry (. (. db-cache cache-data) get id)]
              cache-entry
              (do
                (db-cache-put db-cache id new-obj)
                (construction-fn new-obj))))
          nil)))))


(defn db-cache-remove [db-cache ^Long id]
  "Removes object based on ID from DB-CACHE."
  (let [id (Long. id)] ;; Because (. (Int. 261) equals 261) => false
    (locking db-cache
      (. (. db-cache cache-data)
         remove id))))


(defn db-put
  ([object table-name]
     (db-put object table-name true))
  ([object table-name update-cache?]
     (db-backend-put object
                     (db-get-cache table-name)
                     update-cache?)))


;; TODO: Probably deprecated in place for WITH-DB-OBJ.
(defn db-get
  "Sync; blocking.
CONSTRUCTION-FN is called with the resulting (returning) object as argument on cache miss."
  ([id table-name]
     (db-get id table-name (fn [obj] obj)))
  ([id table-name construction-fn]
     (db-cache-get (db-get-cache table-name) id construction-fn)))


(defn %with-db-obj [id table-name body-fn]
  (let [db-cache (db-get-cache table-name)]
    (with-query-results res [(str "SELECT * FROM " (as-quoted-identifier \" table-name) " WHERE id = ? LIMIT 1 FOR UPDATE;") id]
      (if-let [res (first res)]
        (let [fresh-obj ((. db-cache constructor-fn) db-cache id)]
          (do
            (dbg-prin1 res)
            (body-fn fresh-obj)))
        (throw (Exception. (str "%WITH-DB-OBJ: No object with ID " id " found in `" table-name "'")))))))


(defmacro with-db-obj [obj-sym id table-name & body]
  `(%with-db-obj ~id ~table-name (fn [~obj-sym] ~@body)))


#_(with-sw-db
  (with-db-obj auction-model 4255 "auctions"
    (println "hi!")))



;; TODO:
(defn db-remove [id table-name]
  #_(db-backend-remove id table-name))



;;;;;;;;;;;;;;;;;;;;;;
;; Some quick tests...


(defn test-cache-perf [num-iterations object-id]
  (def -db-cache- (mk-db-cache "test"
                               (fn [db-cache id]
                                 (ref {:value (vm "default")}))
                               nil
                               nil))
  (let [first-done? (promise)]
    (db-cache-get -db-cache- object-id
                  (fn [obj cache-state]
                    (dbg-prin1 [:db-cache-get-cb obj cache-state])
                    (deliver first-done? :yup)))
    (deref first-done?)
    (println "Cache is now hot; request object with ID" object-id "from it" num-iterations "times and print total time taken..")
    (time
     (dotimes [i num-iterations]
       (db-cache-get -db-cache- object-id
                     (fn [obj cache-state]
                       (dbg-prin1 [obj cache-state])))))))


(defn test-cache-insert []
  (def -db-cache- (mk-db-cache "test"
                               (fn [db-cache id]
                                 (println "hum")
                                 (ref {:value (vm "default value")}))
                               nil
                               nil))
  (let [new-obj (ref {:value (vm "non-random initial value")})]
    ;; SQL INSERT.
    (dosync
     (db-backend-put new-obj -db-cache- (fn [new-obj]
                                          (dosync (dbg-prin1 @new-obj)))))
    (Thread/sleep 1000)
    (dosync
     (dbg-prin1 @new-obj)
     (db-cache-get -db-cache- @(:id @new-obj)
                   (fn [obj cache-state]
                     (dbg-prin1 [obj cache-state]))))
    ;; SQL UPDATE.
    (dosync
     (vm-set (:value @new-obj) (str "rand-int: " (rand-int 9999)))
     (dbg-prin1 @(:value @new-obj)))))
