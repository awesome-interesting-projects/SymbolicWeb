(in-ns 'symbolicweb.core)

(declare render-html)
(declare add-branch)
(declare empty-branch)
(declare remove-branch)


(defn ensure-content-str [content]
  (if (widget? content)
    (render-html content)
    content))


(defn jqHTML
  ([widget] (str "$('#" (widget-id-of widget) "').html();"))
  ([widget new-html]
     (add-response-chunk (str "$('#" (widget-id-of widget) "').html(" (url-encode-wrap (str new-html)) ");")
                         widget)))


(defn jqAttr
  ([widget attribute-name]
     (str "$('#" (widget-id-of widget) "').attr('" attribute-name "');"))
  ([widget attribute-name value]
     (add-response-chunk (str "$('#" (widget-id-of widget) "').attr('" attribute-name "'"
                              ", " (url-encode-wrap value) ");")
                         widget)))


(defn jqAppend [parent content]
  "Inside."
  (when (widget? content)
    (add-branch parent content))
  (let [content (ensure-content-str content)]
    (add-response-chunk (str "$('#" (widget-id-of parent) "').append(" (url-encode-wrap content) ");")
                        parent)))


(defn jqPrepend [parent content]
  "Inside."
  (when (widget? content)
    (add-branch parent content))
  (let [content (ensure-content-str content)]
    (add-response-chunk (str "$('#" (widget-id-of parent) "').prepend(" (url-encode-wrap content) ");")
                        parent)))


(defn jqAfter [widget content]
  "Outside."
  (when (widget? content)
    (add-branch (:parent @widget) content))
  (let [content (ensure-content-str content)]
    (add-response-chunk (str "$('#" (widget-id-of widget) "').after(" (url-encode-wrap content) ");")
                        widget)))


(defn jqBefore [widget content]
  "Outside."
  (when (widget? content)
    (add-branch (:parent @widget) content))
  (let [content (ensure-content-str content)]
    (add-response-chunk (str "$('#" (widget-id-of widget) "').before(" (url-encode-wrap content) ");")
                        widget)))


(defn jqAddClass [widget class-name]
  (add-response-chunk (str "$('#" (widget-id-of widget) "').addClass(" (url-encode-wrap class-name) ");")
                      widget))


(defn jqRemoveClass [widget class-name]
  (add-response-chunk (str "$('#" (widget-id-of widget) "').removeClass(" (url-encode-wrap class-name) ");")
                      widget))


(defn jqEmpty [widget]
  (add-response-chunk (str "$('#" (widget-id-of widget) "').empty();")
                      widget)
  (empty-branch widget))


(defn jqRemove [widget]
  (add-response-chunk (str "$('#" (widget-id-of widget) "').remove();")
                      widget)
  (remove-branch widget))