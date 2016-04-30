(in-ns 'symbolicweb.core)

(declare ref? observe %vm-deref)


;;; NOTES:
;;
;;   * ADD-WATCH etc. is pointless because (clojure.lang.LockingTransaction/isRunning) yields FALSE when called from
;;     the ADD-WATCH callback.


;;; TODO:
;;
;;   * The VALUE field should be a Fn; not a Ref. That Fn could close over a Ref -- or something else.
;;   * ENSURE is always used at the moment. Perhaps a dynamic var could be used to flip between ENSURE and DEREF.
;;   * ..and further, perhaps the same thing colud be done with regards to REF-SET, ALTER and COMMUTE.



(defprotocol IValueModel
  (vm-set [vm new-value])) ;; Get is DEREF or @ (via clojure.lang.IDeref).



(deftype ValueModel [^Ref value
                     ^Observable observable]

  ;; Getter.
  clojure.lang.IDeref
  (deref [value-model]
    (%vm-deref value-model value))


  ;; Setter.
  IValueModel
  (vm-set [value-model new-value]
    (let [old-value (ensure value)]
      (ref-set value new-value)
      (notify-observers observable old-value new-value))
    new-value))



(defmethod print-method ValueModel [^ValueModel value-model stream]
  (print-method (.value value-model) stream))



(defn vm-observe [^ValueModel value-model lifetime ^Boolean initial-sync? ^Fn callback]
  "  LIFETIME: If given an instance of Lifetime, observation will start once that Lifetime is activated and lasts until it is
deactivated. If given FALSE, observation will start at once and last forever or as long as VALUE-MODEL exists.

  INITIAL-SYNC?: If TRUE, CALLBACK will be triggered once as soon as observation starts.

  CALLBACK: (fn [inner-lifetime old-value new-value] ..)

Returns a (new) instance of Lifetime if LIFETIME was an instance of Lifetime, or FALSE otherwise. This is also the value passed
as the first argument to CALLBACK."
  (let [observe-res (observe (.observable value-model) lifetime callback)]
    (when initial-sync?
      (letfn [(initial-sync []
                (callback observe-res ::initial-update @value-model))]
        (if observe-res
          (add-lifetime-activation-fn observe-res (fn [_] (initial-sync)))
          (initial-sync))))
    observe-res))



(defn %vm-deref [^ValueModel value-model ^Ref value]
  (do1 (ensure value)
    ;; In context of WITH-OBSERVABLE-VMS?
    (when (and *observed-vms-ctx*
               (not (get (ensure (:vms *observed-vms-ctx*)) value-model))) ;; Not already observed?
      (alter (:vms *observed-vms-ctx*) conj value-model)
      (let [observed-vms-ctx *observed-vms-ctx*]
        (vm-observe value-model (:lifetime observed-vms-ctx) true
                    (fn [& _]
                      ;; Avoid infinite recursion; body-fn triggering a change that leads back to the same body-fn.
                      (when-not (get *observed-vms-active-body-fns* (:body-fn observed-vms-ctx))
                        (binding [*observed-vms-ctx* observed-vms-ctx
                                  *observed-vms-active-body-fns* (conj *observed-vms-active-body-fns*
                                                                       (:body-fn observed-vms-ctx))]
                          (vm-set (:retval observed-vms-ctx)
                                  ((:body-fn observed-vms-ctx)))))))))))



(defn vm
  (^ValueModel [value]
   (vm value =))

  (^ValueModel [value ^Fn cmp-fn]
   (ValueModel. (ref value)
                (mk-Observable (fn [^Observable observable old-value new-value]
                                 (when-not (cmp-fn old-value new-value)
                                   (doseq [^Fn observer-fn (ensure (.observers observable))]
                                     (observer-fn old-value new-value))))))))



(defn vm-alter [^ValueModel value-model ^Fn fn & args]
  (vm-set value-model (apply fn @value-model args)))



(defn ^ValueModel vm-copy [^ValueModel value-model]
  "Creates a ValueModel. The initial value of it will be extracted from VALUE-MODEL. Further changes (mutation of) to
VALUE-MODEL will not affect the ValueModel created and returned here.
See VM-SYNC if you need a copy that is synced with the original VALUE-MODEL."
  (vm @value-model))



(defn ^ValueModel vm-sync
  "Returns a new ValueModel which is kept in one-way sync from VALUE-MODEL via CALLBACK.

  CALLBACK: (fn [inner-lifetime old-value new-value] ..)
            Return value of CALLBACK will be the continuous value of the returned ValueModel.

  LIFETIME: The lifetime of this connection is governed by LIFETIME and can be an instance of Lifetime or NIL for 'infinite'
lifetime (as long as VALUE-MODEL exists)."
  ([^ValueModel value-model lifetime ^Fn callback]
   (vm-sync value-model lifetime callback true))

  ([^ValueModel value-model lifetime ^Fn callback ^Boolean initial-sync?]
   (let [^ValueModel mid (vm nil)]
     (vm-observe value-model lifetime initial-sync?
                 #(vm-set mid (apply callback %&)))
     mid)))



(defn ^ValueModel vm-syncs
  "  CALLBACK takes no arguments."
  ([value-models lifetime ^Fn callback]
   (vm-syncs value-models lifetime callback true))

  ([value-models lifetime ^Fn callback ^Boolean initial-sync?]
   (let [^ValueModel mid (vm nil)
         ^Atom already-synced? (atom false)] ;; We only want to trigger an initial sync once if at all.
     (doseq [^ValueModel value-model value-models]
       (vm-observe value-model lifetime (if (and initial-sync? (not @already-synced?))
                                          (do
                                            (reset! already-synced? true)
                                            true)
                                          false)
                   (fn [& _] (vm-set mid (callback)))))
     mid)))



(defmacro ^ValueModel vm-dbg-prin1 [form]
  `(let [res# ~form]
     (vm-observe res# nil true
                 #(println '~form "::" %2 "=>" %3))
     res#))



(defn %with-observed-vms [lifetime ^Fn body-fn]
  (let [retval (vm nil)]
    (binding [*observed-vms-ctx* {:vms (ref #{})
                                  :retval retval
                                  :lifetime lifetime
                                  :body-fn body-fn}
              *observed-vms-active-body-fns* (conj *observed-vms-active-body-fns* body-fn)]
      (vm-set retval (body-fn)))
    retval))



(defmacro with-observed-vms [lifetime & body]
  `(%with-observed-vms ~lifetime (fn [] ~@body)))
