(in-ns 'symbolicweb.core)


(defn ^WidgetBase mk-HTMLElement [^ValueModel value-model
                                  ^Fn render-fn
                                  ^Fn observer-fn
                                  widget-base-args]
  "  RENDER-FN: (fn [widget] ..)
  OBSERVER-FN: (fn [widget value-model old-value new-value] ..)"
  (with1 (mk-WidgetBase render-fn widget-base-args)
    (vm-observe value-model (.lifetime it) true
                (fn [^Lifetime lifetime old-value new-value]
                  (observer-fn it old-value new-value)))))



(defn ^WidgetBase mk-he [^String html-element-type ^ValueModel value-model
                         & {:keys [observer-fn widget-base-args]
                            :or {widget-base-args {}
                                 observer-fn (fn [^WidgetBase widget old-value new-value]
                                               (jqHTML widget (if (.escape-html? widget)
                                                                (escape-html new-value)
                                                                new-value)))}}]
  (mk-HTMLElement value-model
                  (fn [^WidgetBase widget] (str "<" html-element-type " id='" (.id widget) "'></" html-element-type ">"))
                  observer-fn
                  widget-base-args))



(defn ^WidgetBase mk-Button [label & widget-base-args]
  "LABEL: \"Some Label\" or (vm \"Some Label\")"
  (mk-he "button"
         (if (= ValueModel (class label))
           label
           (vm label))
         :widget-base-args (merge {:escape-html? false} (apply hash-map widget-base-args))))



(defn ^WidgetBase mk-Link [^ValueModel url-mapper-vm ^String url-mapper-name ^ValueModel url-mapper-mutator
                           ^WidgetBase container-view]
  (let [query-str-vm (vm "")]

    (with-observed-vms (.lifetime container-view)
      (when-let [viewport (viewport-of container-view)]
        (vm-set query-str-vm (ring.util.codec/form-encode (merge @(:query-params @viewport)
                                                                 {url-mapper-name @url-mapper-mutator})))))

    (vm-observe query-str-vm (.lifetime container-view) false
                (fn [_ _ query-str]
                  (jqAttr container-view "href"
                          (str "window.location.pathname + '?' + " (url-encode-wrap query-str)))))

    (set-event-handler "click" container-view
                       (fn [& _]
                         (vm-set url-mapper-vm @url-mapper-mutator))
                       :js-before "event.preventDefault(); return(true);"))

  container-view)
