(in-ns 'symbolicweb.core)



;; TODO: Code in mk-WB is very similar to this.
(defn ^WidgetBase %mk-HTMLContainer [^Keyword html-element-type args ^Fn content-fn]
  (mk-WidgetBase (fn [^WidgetBase html-container]
                   (binding [*in-html-container?* html-container] ;; Target for calls to SW done in CONTENT-FN.
                     (if (empty? args)
                       (let [html-element-type (name html-element-type)]
                         (str "<" html-element-type " id='" (.id html-container) "'>"
                              (content-fn html-container)
                              "</" html-element-type ">"))
                       (html
                        [html-element-type (let [attrs (:html-attrs args)]
                                             (if-let [id (:id attrs)]
                                               attrs
                                               (assoc attrs :id (.id html-container))))
                         (content-fn html-container)]))))
                 ;; :ID from :HTML-ATTRS (if supplied) should be used as ID server side also.
                 (if-let [id (:id (:html-attrs args))]
                   (assoc args :id id)
                   args)))



(defmacro whc [[html-element-type args] & body]
  "WITH-HTML-CONTAINER. Some examples:

 (swsync
  (render-html
   (whc [:div {:html-attrs {:style (style {:color 'red})}}]
     \"Hello World! This is: \" (.id html-container))))
 \"<div id=\"sw-15570\" style=\"color: red;\">Hello World! This is: sw-15570</div>\"

 (swsync
  (let [some-model (vm \"hello\")
        some-widget (mk-span some-model)]
    (render-html
     (whc [:div]
      [:p \"Here's some widget: \" (sw some-widget)]))))
 \"<div id='sw-15591'><p>Here's some widget: <span id='sw-15590'></span></p></div>\"
"
  `(%mk-HTMLContainer ~html-element-type
                      ~args
                      (fn [^WidgetBase ~'html-container]
                        (html ~@body))))



(defn ^WidgetBase mk-PostHTMLTemplate [^String id ^Fn content-fn & args]
  "This applies templating to an already existing HTML element, specified by ID, on the page."
  (with1 (%mk-HTMLContainer :%PostHTMLTemplate
                            (into args (list id :id)) ;; TODO: This seems hacky.
                            content-fn)
    (render-html it)))
