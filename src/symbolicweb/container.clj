(in-ns 'symbolicweb.core)


(defn ensure-visible [child parent]
  "Ensure CHILD and its children in turn is declared visible in context of PARENT.
This will also call any FNs stored in :ON-VISIBLE-FNS for the children in question."
    (let [viewport (if (= parent :root)
                     *viewport*
                     (:viewport @parent))
          child-m @child]
      (alter viewport update-in [:widgets] assoc (:id child-m) child) ;; DOM-events will find the widget now.
      (alter child assoc :viewport viewport) ;; Widget will know which Viewport to send JS code to now.
      ((:connect-model-view-fn child-m) (:model child-m) child)
      (doseq [on-visible-fn (:on-visible-fns child-m)]
        (on-visible-fn))
      (doseq [child-of-child (:children child-m)]
        (ensure-visible child-of-child child))))


(defn ensure-non-visible [widget]
  "Remove WIDGET and its children from the DOM."
  (let [widget-m @widget]
    ((:disconnect-model-view-fn widget-m) widget)
    ;; Remove WIDGET from children of parent of WIDGET.
    (when (:parent widget-m) ;; (:root-element Viewport) doesn't have a parent.
      (alter (:parent widget-m)
             assoc :children (remove widget (:children (:parent widget-m)))))
    (doseq [child (:children widget-m)]
      (ensure-non-visible child))
    (alter (:viewport widget-m) update-in [:widgets]
           dissoc (:id widget-m))
    (alter widget assoc
           :parent nil
           :viewport nil
           :children [])))


(defn add-branch [parent child]
  "Declare CHILD to be a part of PARENT.
This is used to track visibility on the server-end. Use e.g. jqAppend to actually display the widget on the client
end."
  (let [parent-m (if (= parent :root)
                   :root
                   @parent)
        child-m @child]
    (assert (not (:parent child-m)))
    (alter child assoc :parent parent)
    (when-not (= parent :root)
      (alter parent update-in [:children] conj child))
    ;; When PARENT is visible, the CHILD and its children in turn should be declared visible too.
    (when (or (= :root parent) (:viewport parent-m))
      (ensure-visible child parent))))


(defn remove-branch [branch-root-node]
  "Remove BRANCH-ROOT-NODE and its children."
  (ensure-non-visible branch-root-node))


(defn empty-branch [branch-root-node]
  "Remove children from BRANCH-ROOT-NODE."
  (doseq [child (:children @branch-root-node)]
    (remove-branch child)))


(defn clear-root []
  (dosync
   (jqEmpty (root-element))))
