(in-ns 'symbolicweb.core)

;; TODO:
;; * Input parsing should be or happen on the Model end so data flowing from back-ends (DBs) can benefit from
;;   it too. Uhm, I think.

;; * Better and more flexible parameter handling; static-attributes/CSS etc..

;; * Error handling and feedback to user.


(derive ::TextInput ::HTMLElement)
(defn make-TextInput [model & attributes]
  (with1 (apply make-HTMLElement "input" model
                :type ::TextInput
                :static-attributes {:type "text"}
                :handle-model-event-fn (fn [widget _ new-value]
                                         (jqVal widget new-value))
                attributes)
    (set-event-handler "change" it
                       (fn [& {:keys [new-value]}]
                         (set-value model (if-let [input-parsing-fn (:input-parsing-fn @it)]
                                            (input-parsing-fn new-value)
                                            new-value)))
                       :callback-data {:new-value "' + encodeURIComponent($(this).val()) + '"})))


(derive ::HashedTextInput ::HTMLElement)
(defn make-HashedTextInput [model & attributes]
  (with1 (apply make-TextInput model
                :handle-model-event-fn (fn [_ _ _])
                :static-attributes {:type "password"}
                attributes)
    (set-event-handler "change" it
                       (fn [& {:keys [new-value]}]
                         (set-value model (if-let [input-parsing-fn (:input-parsing-fn @it)]
                                            (input-parsing-fn new-value)
                                            new-value)))
                       :callback-data {:new-value "' + encodeURIComponent($.sha256($(this).val())) + '"})))


(derive ::IntInput ::TextInput)
(defn make-IntInput [model & attributes]
  (apply make-TextInput model
         :type ::IntInput
         :input-parsing-fn #(Integer/parseInt %)
         attributes))


(derive ::TextArea ::HTMLElement)
(defn make-TextArea [model & attributes]
  (with1 (apply make-HTMLElement "textarea" model
                :type ::TextArea
                :handle-model-event-fn (fn [widget _ new-value]
                                         (jqVal widget new-value))
                attributes)
    (set-event-handler "change" it
                       (fn [& {:keys [new-value]}]
                         (set-value model (if-let [input-parsing-fn (:input-parsing-fn @it)]
                                            (input-parsing-fn new-value)
                                            new-value)))
                       :callback-data {:new-value "' + encodeURIComponent($(this).val()) + '"})))


(derive ::CKEditor ::HTMLElement)
(defn make-CKEditor [model & attributes]
  (apply make-TextArea model
         :type ::CKEditor
         :render-aux-js-fn (fn [widget]
                             (let [w-m @widget
                                   id (:id w-m)]
                               (str "CKEDITOR.replace('" id "');"
                                    "CKEDITOR.instances['" id "'].on('blur', function(e){"
                                    "  if(CKEDITOR.instances['" id "'].checkDirty()){"
                                    "    CKEDITOR.instances['" id "'].updateElement();"
                                    "    $('#" id "').trigger('change');"
                                    "  }"
                                    "  CKEDITOR.instances['" id "'].resetDirty();"
                                    "});")))
         attributes))
