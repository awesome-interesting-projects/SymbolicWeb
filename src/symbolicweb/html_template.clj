(in-ns 'symbolicweb.core)



(defn ^WidgetBase mk-HTMLTemplate [^org.jsoup.nodes.Document html-resource
                                   ^Fn content-fn
                                   & args]
  "Bind Widgets to existing, static HTML.

  CONTENT-FN is something like:

  (fn [html-template]
    [\".itembox\" html-template
     \".title\" (mk-p title-model)
     \".picture\" [:attr :src \"logo.png\"]
     \"#sw-js-bootstrap\" (sw-js-bootstrap)]) ;; String."
  (mk-WidgetBase
   (fn [^WidgetBase template-widget]
     (let [transformation-data (content-fn template-widget) ;; NOTE: Using a Vector since it maintains order; Clojure Maps do not.
           html-resource (.clone html-resource)] ;; Always manipulate a copy to avoid any concurrency problems.
       (doseq [[^String selector content] (partition 2 transformation-data)]
         (when-let [^org.jsoup.nodes.Element element
                    (with (.select html-resource selector)
                      (if (zero? (count it))
                        (do
                          (println "mk-HTMLTemplate: No element found for" selector "in context of" (.id template-widget))
                          nil)
                        (do
                          (assert (= 1 (count it))
                                  (str "mk-HTMLTemplate: " (count it) " (i.e. not 1) elements found for for" selector
                                       "in context of" (.id template-widget)))
                          (.first it))))]
           (let [content-class (class content)]
             (cond ;; COND like this is as of Clojure 1.5 faster than e.g. (case (.toString (class content)) ...).
              (= java.lang.String content-class)
              (.text element content)

              (= clojure.lang.PersistentVector content-class)
              (let [cmd (first content)]
                (case cmd
                  :attr
                  (let [[^Keyword attr-key ^String attr-value] (rest content)]
                    (.attr element (name attr-key) attr-value))

                  :html
                  (.html element ^String (second content))))

              (= symbolicweb.core.WidgetBase content-class)
              (do
                (.attr element "id" ^String (.id ^WidgetBase content))
                (when-not (= content template-widget)
                  (binding [*in-html-container?* template-widget]
                    (attach-branch template-widget content))))))))
       (.html (.select html-resource "body"))))
   (apply hash-map args)))



(defn ^WidgetBase mk-TemplateElement [^ValueModel value-model & widget-base-args]
  "TemplateElements are meant to be used in context of HTMLContainer and its subtypes."
  (mk-he "%TemplateElement" value-model
         :widget-base-args (apply hash-map widget-base-args)))


(defn ^WidgetBase mk-te [^ValueModel value-model & args]
  "Short for mk-TemplateElement."
  (apply mk-TemplateElement value-model args))



(defn ^WidgetBase mk-BlankTemplateElement [& args]
  "A TemplateElement which doesn't have a Model.
This might be used to setup a target for DOM events on some static content from a template."
  (mk-WidgetBase (fn [_]) (apply hash-map args)))


(defn ^WidgetBase mk-bte [& args]
  "Short for mk-BlankTemplateElement."
  (apply mk-BlankTemplateElement args))
