(in-ns 'symbolicweb.core)

;;;; This is a "row-based" (via ID column) reactive cache or DAO layer for DB tables
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; This maps a DB row to a Clojure Ref holding a map where the values might be ValueModels or ContainerModels (SQL array refs).
;;
;; It further sets up reactive connections between the Clojure side ValueModels or ContainerModels and their associated
;; DB entries; meaning any change to the content of these objects will be synced to the DB.
;;
;;
;; * DB-PUT: SQL INSERT.
;; * DB-GET: SQL SELECT.
;; * DB-ENSURE-PERSISTENT-VM-FIELD: SQL UPDATE.
;; * DB-ENSURE-PERSISTENT-CM-FIELD: SQL UPDATE.   (SQL array)
;;
;;
;; TODO: ContainerModel based abstraction for SQL queries? This probably belongs in a different file; it's a different concept.
;; TODO: Prefixing everything here with DB- is retarded; use a namespace.



(defn db-db-array-to-clj-vector [^java.sql.Array db-array]
  "DB (SQL Array) --> SW (Clj Vector)"
  (vec (.getArray db-array)))


(defn ^String db-clj-coll-to-db-array [clj-coll ^String db-type]
  "SW (Clj Coll) --> DB (SQL Array)"
  (cl-format false "ARRAY[~{~S~^, ~}]::~A[]" clj-coll db-type))


(defn ^String db-clj-cm-to-db-array [^ContainerModel container-model ^String db-type]
  "SW (ContainerModel) --> DB (SQL Array)"
  (with-local-vars [elts []]
    (cm-iterate container-model _ obj
      (var-alter elts conj (with1 @(:id @obj)
                             (assert (integer? it)
                                     (str "DB-CLJ-CM-TO-DB-ARRAY: Object not in DB yet? :ID is not an integer: " it))))

      false)
    (cl-format false "ARRAY[~{~S~^, ~}]::~A[]" (var-get elts) db-type)))



(defn db-default-clj-to-db-transformer [m]
  "SW --> DB"
  (assoc m
    :key (cond
          (not (:key m))
          nil

          (= :id (:key m))
          nil

          true
          (keyword (str/replace (name (:key m)) \- \_)))

    :value (cond
            (isa? (class (:value m)) ValueModel)
            @(:value m)

            ;; TODO: Perhaps :VALUE should be a FN?
            ;; NOTE: DB-PUT'ing objects with CM fields not possible if we do this here; it's too early with regards to :ID fields.
            ;; (isa? (class clj-value) ContainerModel)
            ;; (db-clj-cm-to-db-array clj-value "bigint") ;; TODO: Magic value "bigint".

            true
            (:value m))))



(defn db-clj-to-db-transformer [^DBCache db-cache ^Ref obj ^Keyword clj-key clj-value]
  "SW --> DB"
  (let [m {:db-cache db-cache :obj obj :key clj-key :value clj-value}]
    (if-let [^Fn f (.db-clj-to-db-transformer-fn db-cache)]
      (f m)
      (db-default-clj-to-db-transformer m))))



(declare db-value-to-vm-handler)
(defn db-default-db-to-clj-transformer [m]
  "DB --> SW"
  (assoc m
    :key (keyword (str/replace (name (:key m)) \_ \-)) ;; :some_field --> :some-field
    :handler
    (fn [clj-key db-value]
      (cond
       (isa? (class db-value) java.sql.Array)
       (assert false
               "DB-DEFAULT-DB-TO-CLJ-HANDLER: Can't call DB-VALUE-TO-CM-HANDLER here; REF-DB-TABLE-NAME arg missing.")

       ;; Existing field found, but it is not a VM; store as raw DB-VALUE.
       (and (find @(:obj m) clj-key)
            (not (isa? (class (clj-key @(:obj m))) ValueModel)))
       (alter (:obj m) assoc clj-key db-value)

       ;; Assume the intent is to store the value in a new or existing VM.
       true
       (db-value-to-vm-handler (:db-cache m) (:obj m) clj-key db-value)))))



(defn db-db-to-clj-transformer [^DBCache db-cache ^Ref obj ^Keyword db-key db-value]
  "DB --> SW"
  (let [m {:db-cache db-cache :obj obj :key db-key :value db-value}]
    (if-let [^Fn f (.db-db-to-clj-transformer-fn db-cache)]
      (f m)
      (db-default-db-to-clj-transformer m))))



(declare db-ensure-persistent-vm-field) (declare db-ensure-persistent-cm-field)
(defn db-db-to-clj-handler [^DBCache db-cache ^Ref obj ^Keyword db-key db-value ^Boolean ensure-persistent?]
  "DB --> SW"
  (let [res (db-db-to-clj-transformer db-cache obj db-key db-value)]
    (when (:key res)
      (with ((:handler res) (:key res) (:value res))
        (when ensure-persistent?
          (cond
           (isa? (class it) ValueModel)
           (db-ensure-persistent-vm-field db-cache obj (:key res) it)

           (isa? (class it) ContainerModel)
           (db-ensure-persistent-cm-field db-cache obj (:key res) it)))))))



(defn db-db-to-clj-entry-handler [^DBCache db-cache ^Ref obj entry-data ^Boolean ensure-persistent?]
  "DB --> SW
  ENTRY-DATA: Map representing a DB Row; DB-KEY and DB-VALUEs."
  (doseq [[^Keyword db-key db-value] entry-data]
    (db-db-to-clj-handler db-cache obj db-key db-value ensure-persistent?)))



(defn db-ensure-persistent-vm-field [^DBCache db-cache ^Ref obj ^Keyword clj-key ^ValueModel value-model]
  "Sets up reactive SQL UPDATEs for VALUE-MODEL."
  (vm-observe value-model nil false
              (fn [inner-lifetime old-value new-value]
                (let [res (db-clj-to-db-transformer db-cache obj clj-key value-model)
                      ^Keyword db-key (:key res)
                      db-value (:value res)]
                  (db-update (.table-name db-cache) {db-key db-value}
                             `(= :id ~(deref (:id @obj))))))))



(declare db-put)
(declare db-remove)
(defn db-ensure-persistent-cm-field
  "Sets up reactive SQL UPDATEs for CONTAINER-MODEL."
  ([^DBCache db-cache ^Ref obj ^Keyword clj-key ^ContainerModel container-model]
     (db-ensure-persistent-cm-field db-cache obj clj-key container-model false))

  ([^DBCache db-cache ^Ref obj ^Keyword clj-key ^ContainerModel container-model ^Boolean initial-sync?]
     (letfn [(do-it []
               (let [res (db-clj-to-db-transformer db-cache obj clj-key container-model)
                     db-key (:key res)
                     db-value (db-clj-cm-to-db-array (:value res) "bigint")] ;; TODO: Magic value.
                 (db-stmt (str "UPDATE " (.table-name db-cache) " SET " (name db-key) " = " db-value
                               " WHERE id = " @(:id @obj) ";"))))]

       (when initial-sync?
         (cm-iterate container-model _ obj
           (db-put obj (:db-table-name @obj)) ;; This ensures that each member has a value for :ID.
           false)
         (do-it))

       (observe (.observable container-model) nil
                (fn [inner-lifetime args]
                  (let [[event-sym & event-args] args
                        [op-type op-obj] (case event-sym
                                           cm-prepend
                                           [:add (cmn-data (nth event-args 0))]

                                           (cmn-after cmn-before)
                                           [:add (cmn-data (nth event-args 1))]

                                           cmn-remove
                                           [:remove (cmn-data (nth event-args 0))])]
                    (case op-type
                      :add (db-put op-obj (:db-table-name @op-obj))
                      :remove (db-remove @(:id @op-obj) (:db-table-name @op-obj)))
                    (do-it)))))))



(defn db-value-to-vm-handler [^DBCache db-cache ^Ref obj ^Keyword clj-key db-value]
  "DB --> SW
Returns the ValueModel."
  (if-let [^ValueModel existing-vm (clj-key (ensure obj))] ;; Does field already exist in OBJ?
    (do
      (when-not (isa? (class existing-vm) ValueModel)
        (println "DB-VALUE-TO-VM-HANDLER:"
                 (class existing-vm)
                 "," (.table-name db-cache)
                 "," clj-key
                 "," db-value))
      (vm-set existing-vm db-value)
      existing-vm)
    (let [^ValueModel new-vm (vm db-value)]
      (ref-set obj (assoc (ensure obj) clj-key new-vm))
      new-vm)))



(declare db-get)
(defn db-value-to-cm-handler [^DBCache db-cache ^Ref obj ^Keyword clj-key ^java.sql.Array db-value
                              ^String ref-db-table-name]
  "DB --> SW
Returns the ContainerModel"
  (let [^clojure.lang.PersistentVector cm-objs (mapv #(db-get % ref-db-table-name)
                                                     (db-db-array-to-clj-vector db-value))]
    (if-let [^ContainerModel existing-cm (clj-key (ensure obj))] ;; Does field already exist in OBJ?
      (do
        (assert (zero? (count existing-cm)))
        (doseq [cm-obj cm-objs]
          (cm-append existing-cm (cmn cm-obj)))
        existing-cm)
      (with1 (cm)
        (doseq [cm-obj cm-objs]
          (cm-append it (cmn cm-obj)))))))



(defn db-backend-remove [^DBCache db-cache ^Long id]
  (db-stmt (str "DELETE FROM " (.table-name db-cache) " WHERE id = " id ";")))



(defn ^Ref db-backend-get [^DBCache db-cache ^Long id ^Ref obj]
  "Used by DB-GET; see DB-GET.
Returns OBJ or NIL"
  ;;(println "DB-BACKEND-GET:" id "(" (.table-name db-cache) ")")
  (let [res (db-stmt (str "SELECT * FROM " (.table-name db-cache) " WHERE id = " id " LIMIT 1;"))]
    (when-let [db-row (first res)]
      (db-db-to-clj-entry-handler db-cache obj db-row true)
      obj)))



(declare db-cache-put)
(defn db-backend-put
  ([^Ref obj ^DBCache db-cache] (db-backend-put obj db-cache true))
  ([^Ref obj ^DBCache db-cache ^Boolean update-cache?]
     (when-not @(:id @obj)
       (with-local-vars [record-data {}
                         values-to-escape []
                         after-insert-fns []]
         (doseq [[^Keyword clj-key clj-value] (ensure obj)]
           (let [res (db-clj-to-db-transformer db-cache obj clj-key clj-value)
                 ^Keyword db-key (:key res)
                 db-value (:value res)]
             (when db-key
               (cond
                (isa? (class clj-value) ValueModel)
                (var-alter after-insert-fns conj
                           (fn [] (db-ensure-persistent-vm-field db-cache obj clj-key clj-value)))

                (isa? (class clj-value) ContainerModel)
                (var-alter after-insert-fns conj
                           (fn [] (db-ensure-persistent-cm-field db-cache obj clj-key clj-value true))))
               (var-alter record-data assoc db-key db-value))))
         (let [sql (if (pos? (count (var-get record-data)))
                     (cl-format false "INSERT INTO ~A (~{~A~^, ~}) VALUES (~{~A~^, ~}) RETURNING *;"
                                (.table-name db-cache)
                                (mapv name (keys (var-get record-data)))
                                (mapv (fn [v]
                                        (cond
                                         (isa? (class v) ValueModel)
                                         (do1 "?"
                                           (var-alter values-to-escape conj @v))

                                         ;; Dummy value here so we can do our :INSERT without having to :INSERT other objects
                                         ;;first. Doing that would be tricky since those objects might rely on this object
                                         ;; having been :INSERTed first (:ID field).
                                         (isa? (class v) ContainerModel)
                                         "ARRAY[]::bigint[]" ;; TODO: Magic value.

                                         true
                                         (do1 "?"
                                           (var-alter values-to-escape conj v))))
                                      (vals (var-get record-data))))
                     (str "INSERT INTO " (.table-name db-cache) " DEFAULT VALUES;"))
               res (first (apply db-pstmt sql (var-get values-to-escape)))]
           (vm-set (:id @obj) (:id res))
           (when update-cache?
             (db-cache-put db-cache (:id res) obj))
           (doseq [^Fn f (var-get after-insert-fns)] (f)) ;; TODO: Not sure where to place this.
           ;; TODO: The True here leads to multiple calls to DB-ENSURE-PERSISTENT.. for the same fields.
           (db-db-to-clj-entry-handler db-cache obj res true)
           ;; Initialize object further; perhaps add further (e.g. non-DB related) observers of the objects fields etc..
           ((.after-fn db-cache) obj)
           obj)))))



(defn ^DBCache mk-DBCache [^String table-name
                           ^Fn constructor-fn
                           ^Fn after-fn
                           db-clj-to-db-transformer-fn
                           db-db-to-clj-transformer-fn]
  (let [^DBCache db-cache (DBCache.
                           db-clj-to-db-transformer-fn
                           db-db-to-clj-transformer-fn
                           table-name
                           constructor-fn
                           after-fn
                           nil)]
    (set-internal-cache db-cache
                        (-> (CacheBuilder/newBuilder)
                            (.softValues)
                            (.concurrencyLevel (.availableProcessors (Runtime/getRuntime))) ;; TODO: Configurable?
                            (.build)))
    db-cache))



;; TODO: All this seems to suck a bit too much.
(defonce -db-cache-constructors- (atom {})) ;; table-name -> fn
(defonce -db-caches- ;; table-name -> DBCache
  (-> (CacheBuilder/newBuilder)
      (.concurrencyLevel (.availableProcessors (Runtime/getRuntime))) ;; TODO: Configurable?
      (.build (proxy [CacheLoader] []
                (load [table-name]
                  ((get @-db-cache-constructors- table-name)))))))



(defn ^DBCache db-get-cache [^String table-name]
  (.get ^com.google.common.cache.LocalCache$LocalLoadingCache -db-caches- table-name))



(defn ^DBCache db-reset-cache [^String table-name]
  (.invalidate -db-caches- table-name))



(defn db-cache-put [^DBCache db-cache ^Long id ^Ref obj]
  "Store association between ID and OBJ in DB-CACHE.
If ID already exists, the entry will be overwritten."
  (let [id (long id)] ;; Because (.equals (int 261) 261) => false
    (.put (get-internal-cache db-cache) id obj)))



(defn db-cache-remove [^DBCache db-cache ^Long id]
  "Removes object based on ID from DB-CACHE."
  (let [id (long id)] ;; Because (. (Int. 261) equals 261) => false
    (.invalidate (get-internal-cache db-cache) id)))



(defn db-put
  "SQL `INSERT ...'.
Blocking."
  ([^Ref obj ^String table-name]
     (db-put obj table-name true))

  ([^Ref obj ^String table-name ^Boolean update-cache?]
     (db-backend-put obj (db-get-cache table-name) update-cache?)))



(defn ^Ref db-get
  ([^Long id ^String table-name]
     (db-get id table-name identity))

  ([^Long id ^String table-name ^Fn after-construction-fn]
     ;;(println "DB-GET:" id table-name)
     (let [id (long id) ;; Because (.equals (int 261) 261) => false
           ^DBCache db-cache (db-get-cache table-name)
           retval (atom false)]
       (letfn [(grab-it []
                 (with (.get ^com.google.common.cache.LocalCache$LocalLoadingCache (get-internal-cache db-cache) id
                             (fn []
                               (when-let [^Ref new-obj (db-backend-get db-cache id ((.constructor-fn db-cache) db-cache id))]
                                 (after-construction-fn ((.after-fn db-cache) new-obj))
                                 new-obj)))
                   (when (and it (:id @it) @(:id @it)) ;; If the object has a set :ID field, it's complete.
                     (reset! retval it))))]
         (try
           ;; Doing (swsync (db-get 42 "users") (/ 42 0)) will actually add an "unfinished" object to the cache – hence all this.
           (while (not @retval)
             (grab-it)
             (when-not @retval
               (locking db-cache
                 ;;(println (str "DB-GET [" id " " table-name "]: Recovering corrupted cache entry!"))
                 ;; Will either instantly return the same corrupted object or a proper one created by another thread before lock.
                 (grab-it)
                 (when-not @retval
                   (db-cache-remove db-cache id) ;; Get rid of corrupted object..
                   (grab-it))))) ;; ..and try again.
           @retval

           (catch com.google.common.cache.CacheLoader$InvalidCacheLoadException e
             (throw (Exception. (str "DB-GET: Object with ID " id " not found in " (.table-name db-cache)))))
           (catch com.google.common.util.concurrent.UncheckedExecutionException e
             (println (str "DB-GET [" id " " table-name "]: Re-throwing cause " (.getCause e) " of " e))
             (throw (.getCause e))))))))



;; TODO: This is so similar to DB-DELETE it's not even funny. Perhaps I should use a different prefix here e.g. "dao-".
(defn db-remove [^Ref obj ^String table-name]
  "SQL `DELETE FROM ...'."
  (when-let [id (if (ref? obj)
                  @(:id @obj)
                  obj)]
    (let [id (long id) ;; Because (.equals (int 261) 261) => false
          ^DBCache db-cache (db-get-cache table-name)]
      (db-backend-remove db-cache id)
      (db-cache-remove db-cache id))))
