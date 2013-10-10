(in-ns 'symbolicweb.core)


;; TODO: Perhaps it is possible to do this without having a PARENT field? Just pass information about the parent along
;; as an argument to ATTACH-LIFETIME to "store" for the later invocation of the ADD-LIFETIME-ACTIVATION-FN callback.


(defprotocol IWidgetBase
  (viewport-of [widget-base])
  (parent-of [widget-base])

  (attach-branch [widget-base child-widget-base])
  (detach-branch [widget-base])
  (empty-branch [widget-base]))



(deftype WidgetBase [^String id
                     ^Lifetime lifetime
                     ^Fn render-fn
                     ^Ref parent ;; WidgetBase
                     ^ValueModel viewport ;; Viewport
                     ^Ref callbacks ;; {CB-NAME -> [HANDLER-FN CALLBACK-DATA], ...}   (DOM events)
                     ^Boolean escape-html?]
  IWidgetBase
  (viewport-of [_]
    @viewport)


  (parent-of [_]
    (ensure parent))


  (attach-branch [parent child]
    (assert (not (parent-of child))
            (str "ATTACH-BRANCH: CHILD already has a parent assigned for it: " (parent-of child)
                 ", EXISTING-PARENT-ID: " (.id (parent-of child))
                 ", CHILD-ID: " (.id child)))
    ;; CHILD -> PARENT.
    (ref-set (.parent child) parent)
    (attach-lifetime (.lifetime parent) (.lifetime child)))


  (detach-branch [widget]
    (detach-lifetime (.lifetime widget)))


  (empty-branch [widget]
    ;; TODO: This won't do since there might be child Lifetimes "outside of" the Widget hierarchy that shouldn't be removed.
    ;; Another way to think about this is that _we_ aren't dead yet; only our child _wigets_ are.
    #_(doseq [^Lifetime child-lifetime (lifetime-children-of (.lifetime widget))]
        (detach-lifetime child-lifetime))
    ;; ..so, a hack (e.g. can .VIEWPORT return NIL?) instead – we scan all Widgets in the Viewport of WIDGET.
    (when-let [viewport @(.viewport widget)]
      (doseq [some-widget (vals (:widgets @viewport))]
        (when (= widget @(.parent some-widget))
          (detach-branch some-widget))))))



(defn mk-WidgetBase ^WidgetBase  [^Fn render-fn args]
  (with1 (WidgetBase. (or (:id args) ;; ID
                          (str "sw-" (generate-uid)))
                      (if (:root-widget? args) ;; LIFETIME
                        (mk-LifetimeRoot)
                        (mk-Lifetime))
                      render-fn ;; RENDER-FN
                      (ref nil) ;; PARENT
                      (vm nil) ;; VIEWPORT
                      (ref {}) ;; CALLBACKS
                      ;; ESCAPE-HTML?
                      (if-let [entry (find args :escape-html?)]
                        (val entry)
                        true))

    (when-not (:root-widget? args)
      (add-lifetime-activation-fn (.lifetime it)
                                  (fn [^Lifetime lifetime]
                                    (let [parent-viewport (viewport-of (parent-of it))]
                                      ;; Viewport --> Widget (DOM events).
                                      (alter parent-viewport update-in [:widgets]
                                             assoc (.id it) it)
                                      ;; Widget --> Viewport.
                                      (vm-set (.viewport it) parent-viewport)))))

    (add-lifetime-deactivation-fn (.lifetime it)
                                  (fn [^Lifetime lifetime]
                                    (let [viewport (viewport-of it)]
                                      ;; Viewport -/-> Widget (DOM events).
                                      (alter viewport update-in [:widgets]
                                             dissoc (.id it) it)
                                      ;; Widget -/-> Viewport.
                                      (vm-set (.viewport it) nil))))))



(defn ^String render-html [^WidgetBase widget]
  "Return HTML structure which will be the basis for further initialization."
  ((.render-fn widget) widget))



(defn ^String sw [^WidgetBase widget]
  "Render WIDGET as part of a HTMLContainer; WITH-HTML-CONTAINER."
  (attach-branch *in-html-container?* widget)
  (render-html widget))



(defn ^WidgetBase set-event-handler [^String event-type ^WidgetBase widget ^Fn callback-fn
                                     & {:keys [js-before callback-data js-after once?]
                                        :or {js-before "return(true);"
                                             callback-data ""
                                             js-after ""}}]
  "Set an event handler for WIDGET.
Returns WIDGET."
  (if callback-fn
    (do
      ;; TODO: Check if EVENT-TYPE is already bound? Think about this ..
      (alter (.callbacks widget) assoc event-type
             [(if once?
                (comp callback-fn
                      (fn [& args]
                        (alter (.callbacks widget) dissoc event-type)
                        args))
                callback-fn)
              callback-data])
      (add-response-chunk
       (str "$('#" (.id widget) "')"
            ".off('" event-type "')"
            (if once?
              (str ".one('" event-type "', ")
              (str ".on('" event-type "', "))
            "function(event){"
            "swWidgetEvent('" (.id widget) "', '" event-type "', function(){" js-before "}, '"
            (apply str (interpose \& (map #(str (url-encode-component (str %1)) "=" %2)
                                          (keys callback-data)
                                          (vals callback-data))))
            "', function(){" js-after "});"
            "});\n")
       widget)
      widget)
    (do
      (alter (.callbacks widget) dissoc event-type)
      (add-response-chunk (str "$('#" (.id widget) "').off('" event-type "');\n")
                          widget))))
