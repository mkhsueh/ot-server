;;; run it: ./scripts/polling
(ns main
  (:use org.httpkit.server
        [clojure.tools.logging :only [info]]
        [clojure.data.json :only [json-str]]
        [clojure.data.json :as json]
        (compojure [core :only [defroutes GET POST]]
                   [handler :only [site]]
                   [route :only [files not-found]])))

(def ^{:const true} json-header {"Content-Type" "application/json; charset=utf-8"})

;; TODO implement version numbers.
;; TODO use {insert: 'asdf'} {delete: 5} {retain: 5} format.
;; Formatting over a region: {retain: 5, attributes: {italic: true}}

;; List representing document elements for a test document.
(def documentState (atom []))

;; List of historical canonical operations according to the server.
(def history (atom []))

(defn- now [] (quot (System/currentTimeMillis) 1000))

(def clients
  "map channel -> sequence number"
  (atom {}))                 ; a hub, a map of client => sequence number

(let [max-id (atom 0)]
  (defn next-id []
    "ID generator"
    (swap! max-id inc)))

(defonce all-msgs (ref [{:id (next-id),            ; all message, in a list
                         :time (now)
                         :msg "this is a live chatroom, have fun",
                         :author "system"}]))

;; Apply a fast-forward operation.
(defn applyOperation [operation]
  (case (operation :action)
    "delete" ()
    "insert" ()
  )
)


(defn linsertBeforeR [op1 op2]
  (if (< (:elemIndex op1) (:elemIndex op2))
    :linsertBeforeR
    :notRecognized
    )
  )

(defn linsertBeforeRSameLine [op1 op2]
  (if (= (:elemIndex op1) (:elemIndex op2))
    (if (<= (:index op1) (:index op2))
      :linsertBeforeRSameLine
      :notRecognized
      )
    :notRecognized
    )
  )

;; TOOD make sure that things on the same line get checked before this.
(defn lafterR [op1 op2]
  (if (> (:elemIndex op1) (:elemIndex op2))
    :lafterR
    :notRecognized
    )
  )

(defn lInsertInsideRDelete [op1 op2]
  (if-not (and (= "delete" (:action op2)) (= "insert" (:action op1)))
    :notRecognized
    (if-not (= (:elemIndex op1) (:elemIndex op2))
      :notRecognized
      ;; Find if the start index of op1 (insert) is inside the delete region of op2.
      (let [op2Start (:index op2)
            op2End (+ (:index op2) (:length op2))]
        (if (and (> (:index op1) op2Start) (< (:index op1) op2End))
          :lInsertInsideRDelete
          :notRecognized
          )
        )
      )
    )
  )

(defn overlappingDelete [op1 op2]
  (if-not (and (and (= "delete" (:action op1)) (= "delete" (:action op2)))
               (= (:elemIndex op1) (:elemIndex op2)))
    :notRecognized
    (let [rStart (:index op1)
          rEnd (+ (:index op1) (:length op1))
          lStart (:index op2)
          lEnd (+ (:index op2) (:length op2))]
      (if (or
           (or (and (>= rStart lStart) (<= rStart lEnd))
               (and (>= rEnd lStart) (<= rEnd lEnd)))
           (or (and (>= lStart rStart) (<= lStart rEnd))
               (and (>= lEnd rStart) (<= lEnd rEnd)))
           )
        :overlappingDelete
        :notRecognized
        ))))

(defn determineTransformCase [op1 op2]
  ;; Go through all the transform determination functions and accumulate the tranform situation.
  )

;; Transform op2 given op1 operation.
(defn transform [op1 op2]
;; TODO use multimethod approach to dispatch?


  )

;; Compute the merged transformation given a start version and operation to merge.
;; For each historical operation, update the new operation until the final transformation is reached.
(defn computeMerge [start operation]

  )

(defn broadcast [operation]
  ;; Sends the canonical next operation to all participants.
  )

(defn mergeOperation [operation]
  ;; Examples.
  ;; {"action":"insert" "elemIndex": 3 "index": 2 "content": "foo" "version": 1234 "clientId" 1}
  ;; {"action":"delete" "elemIndex": 3 "index": 2 "length": 5 "version": 1234 "clientId"} ; delete 5 forward from index 2
  (let [destructured (json/read-str operation :key-fn keyword)]
    (do
      (info destructured)
      (let [mergeOperation (computeMerge (destructured :version) destructured)]
        (applyOperation mergeOperation)
        ;; Add to history.
        (swap! history (fn [curVal] (conj curVal mergeOperation)))
        ;; TODO broadcast changes.
        ; (broadcast mergeOperation)
        )
      )))

(defn- get-msgs [max-id]
  ;; only the messages client does not have yet: id greater than max-id
  (filter #(> (-> %1 :id) max-id) @all-msgs))


;; Simulate client.
(defn poll-mesg [req]
  (let [id (Integer/valueOf (-> req :params :id))
        msgs (get-msgs id)]
    (if (seq msgs)
      {:status 200 :headers json-header :body (json-str msgs)}
      (with-channel  req channel
        ;; store the channel so other threads can write into it
        ;; notice that we don't need to return anything, the body is just
        ;; executed but a default, async response with the channel is returned
        (swap! clients assoc channel id)
        (on-close channel (fn [status]
                            (swap! clients dissoc channel)))))))

;; Simluate server.
(defn on-mesg-received [req]
  (let [{:keys [msg author]} (-> req :params)
        data {:msg msg :author author :time (now) :id (next-id)}]
    (info "mesg received: " msg)
    ;;(swap! documentState (fn [curVal] (conj curVal msg)))
    (mergeOperation msg)
    (info @documentState)

    ;; add message to the message store
    (dosync (alter all-msgs conj data))
    (doseq [channel (keys @clients)]
      ;; send message to client
      (send! channel {:status 200
                      :headers json-header
                      :body (json-str (get-msgs (@clients channel)))}))
    {:status 200 :headers {}}))

(defroutes chartrootm
  (GET "/poll" [] poll-mesg)
  (POST "/msg" [] on-mesg-received)
  (files "" {:root "static"})
  (not-found "<p>Page not found.</p>" ))

(defn- wrap-request-logging [handler]
  (fn [{:keys [request-method uri] :as req}]
    (let [resp (handler req)]
      (info (name request-method) (:status resp)
            (if-let [qs (:query-string req)]
              (str uri "?" qs) uri))
      resp)))

(defn -main [& args]
  (run-server (-> #'chartrootm site wrap-request-logging) {:port 9898})
  (info "server started. http://127.0.0.1:9898. Try open 2 browser tabs, have a nice chat"))
