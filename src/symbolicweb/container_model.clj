(in-ns 'symbolicweb.core)


(defmacro cm-iterate [container-model cmn-symbol cmn-data-symbol & body]
  "Iterate over CMNs in CONTAINER-MODEL executing BODY for each iteration with CMN-DATA-SYMBOL bound to the CMN-DATA of each
CMN respectively.

Iteration will end whenever BODY returns a True value, or when the tail of CONTAINER-MODEL was reached."
  `(loop [~cmn-symbol (cm-head-node ~container-model)]
     (when ~cmn-symbol
       (let [~cmn-data-symbol (cmn-data ~cmn-symbol)]
         (if-let [ret-value# (do ~@body)]
           ret-value#
           (when-let [right-node# (cmn-right-node ~cmn-symbol)]
             (recur right-node#)))))))



;; Doubly linked list node.
(defprotocol IContainerModelNode
  (cmn-left-node [node])
  (cmn-set-left-node [node new-left-node])
  (cmn-right-node [node])
  (cmn-set-right-node [node new-right-node])

  (cmn-container-model [node])
  (cmn-set-container-model [node new-container-model])

  (cmn-data [node]))



(deftype ContainerModelNode [%container-model
                             ^Lifetime lifetime
                             ^Ref left
                             ^Ref right
                             data]
  IContainerModelNode
  (cmn-left-node [_]
    (ensure left))
  (cmn-set-left-node [_ new-left-node]
    (ref-set left new-left-node))

  (cmn-right-node [_]
    (ensure right))
  (cmn-set-right-node [_ new-right-node]
    (ref-set right new-right-node))

  (cmn-container-model [_]
    (ensure %container-model))
  (cmn-set-container-model [_ new-container-model]
    (ref-set %container-model new-container-model))

  (cmn-data [_]
    data))



;; Doubly linked list.
(defprotocol IContainerModel
  (cm-tail-node [cm])
  (cm-set-tail-node [cm new-tail-node])

  (cm-head-node [cm])
  (cm-set-head-node [cm new-head-node])

  (cm-set-count [cm new-count]))



(deftype ContainerModel [^Lifetime lifetime
                         ^Ref head-node
                         ^Ref tail-node
                         ^Ref %count
                         ^Observable observable]
  clojure.lang.Counted
  (count [_]
    (dosync (ensure %count)))


  IContainerModel
  (cm-tail-node [_]
    (ensure tail-node))

  (cm-set-tail-node [_ new-tail-node]
    (ref-set tail-node new-tail-node))

  (cm-head-node [_]
    (ensure head-node))

  (cm-set-head-node [_ new-head-node]
    (ref-set head-node new-head-node))

  ;; The getter is `count' from `clojure.lang.Count'.
  (cm-set-count [_ new-count]
    (ref-set %count new-count)))



(defn ^ContainerModel mk-ContainerModel []
  (ContainerModel. (mk-Lifetime) ;; LIFETIME
                   (ref nil)     ;; HEAD-NODE
                   (ref nil)     ;; TAIL-NODE
                   (ref 0)       ;; %COUNT
                   ;; OBSERVABLE
                   (mk-Observable (fn [^Observable observable & event-args]
                                    (doseq [^Fn observer-fn (ensure (.observers observable))]
                                      (observer-fn event-args))))))



(defn ^ContainerModel cm []
  (mk-ContainerModel))



(declare cm-prepend cmn-after)
(defn cm-append [^ContainerModel cm ^ContainerModelNode new-node]
  "Add NEW-NODE to end of the contained nodes in CM.
This mirrors the jQuery `append' function:
  http://api.jquery.com/append/"
  ;; http://en.wikipedia.org/wiki/Doubly-linked_list#Inserting_a_node
  ;;
  ;; function insertEnd(List list, Node newNode)
  (if (not (cm-tail-node cm)) ;; if list.lastNode == null
    (cm-prepend cm new-node) ;; insertBeginning(list, newNode)
  ;; else
    (cmn-after (cm-tail-node cm) new-node)) ;; insertAfter(list, list.lastNode, newNode)
  cm)



(declare cmn-before)
(defn cm-prepend [^ContainerModel cm ^ContainerModelNode new-node]
  "Add NEW-NODE to beginning of the contained nodes in CM.
This mirrors the jQuery `prepend' function:
  http://api.jquery.com/prepend/"
  ;; http://en.wikipedia.org/wiki/Doubly-linked_list#Inserting_a_node
  ;;
  ;; function insertBeginning(List list, Node newNode)
  ;;   if list.firstNode == null
  (if (not (cm-head-node cm))
    (do
      (assert (not (cmn-container-model new-node)))
      (cmn-set-container-model new-node cm)
      (cm-set-count cm (inc (count cm)))
      (attach-lifetime (.lifetime cm) (.lifetime new-node))

      (cm-set-head-node cm new-node) ;; list.firstNode := newNode
      (cm-set-tail-node cm new-node) ;; list.lastNode  := newNode
      (cmn-set-left-node new-node nil) ;; newNode.prev := null
      (cmn-set-right-node new-node nil) ;; newNode.next := null

      (notify-observers (.observable cm) 'cm-prepend new-node))
    ;; else
    (cmn-before (cm-head-node cm) new-node)) ;; insertBefore(list, list.firstNode, newNode)
  cm)



(defn cm-clear [^ContainerModel cm]
  "Empty the ContainerModel; calls jqEmpty at the client (View) end."
  (cm-set-count cm 0)
  (cm-iterate cm node _
              (detach-lifetime (.lifetime ^ContainerModelNode node))
              nil)
  (cm-set-head-node cm nil)
  (cm-set-tail-node cm nil)
  (notify-observers (.observable cm) 'cm-clear)
  cm)
