(ns ^:no-doc onyx.peer.function
    (:require [clojure.core.async :refer [chan >! go alts!! close! timeout]]
              [onyx.static.planning :refer [find-task]]
              [onyx.messaging.acking-daemon :as acker]
              [onyx.compression.nippy :refer [compress decompress]]
              [onyx.peer.task-lifecycle-extensions :as l-ext]
              [onyx.peer.pipeline-extensions :as p-ext]
              [onyx.peer.operation :as operation]
              [onyx.extensions :as extensions]
              [taoensso.timbre :refer [debug]]
              [dire.core :refer [with-post-hook!]])
    (:import [java.util UUID]
             [java.security MessageDigest]))

(defn hash-value [x]
  (let [md5 (MessageDigest/getInstance "MD5")]
    (apply str (.digest md5 (.getBytes (pr-str x) "UTF-8")))))

(defn group-message [segment catalog task]
  (let [t (find-task catalog task)]
    (if-let [k (:onyx/group-by-key t)]
      (hash-value (get segment k))
      (when-let [f (:onyx/group-by-fn t)]
        (hash-value ((operation/resolve-fn {:onyx/fn f}) segment))))))

(defn compress-segments [next-tasks catalog result event]
  (assoc result
    :leaves
    (mapv
     (fn [leaf]
       (let [msg (if (and (operation/exception? (:segment leaf))
                          (:post-transformation (:routes leaf)))
                   (operation/apply-fn
                    (operation/kw->fn (:post-transformation (:routes leaf)))
                    [event] (:segment leaf))
                   (:segment leaf))]
         (assoc leaf :hash-group (reduce (fn [groups t]
                                           (assoc groups t (group-message msg catalog t)))
                                         {} next-tasks))))
     (:leaves result))))

(defmethod l-ext/start-lifecycle? :function
  [_ event]
  {:onyx.core/start-lifecycle? (operation/start-lifecycle? event)})

(defmethod l-ext/inject-lifecycle-resources :function
  [_ {:keys [onyx.core/task-map]}]
  {:onyx.function/fn (operation/resolve-fn task-map)})

(defmethod p-ext/read-batch :default
  [{:keys [onyx.core/messenger] :as event}]
  {:onyx.core/batch (onyx.extensions/receive-messages messenger event)})

(defmethod p-ext/apply-fn :default
  [{:keys [onyx.core/params] :as event} segment]
  (operation/apply-fn (:onyx.function/fn event) params segment))

(defmethod p-ext/compress-batch :default
  [{:keys [onyx.core/results onyx.core/catalog onyx.core/serialized-task]
    :as event}]
  (let [next-tasks (keys (:egress-ids serialized-task))
        compressed-msgs (map #(compress-segments next-tasks catalog % event) results)]
    (merge event {:onyx.core/compressed compressed-msgs})))

(defn filter-by-route [messages task-name]
  (->> messages
       (filter (fn [msg] (some #{task-name} (:flow (:routes msg)))))
       (map :segment)))

(defmethod p-ext/write-batch :default
  [{:keys [onyx.core/messenger onyx.core/job-id] :as event}]
  (if (seq (mapcat :leaves (:onyx.core/compressed event)))
    (let [replica @(:onyx.core/replica event)]
      (doseq [[task-name task-id] (:egress-ids (:onyx.core/serialized-task event))]
        (let [peers (get-in replica [:allocations job-id task-id])
              active-peers (filter #(= (get-in replica [:peer-state %]) :active) peers)]
          (when (seq active-peers)
            (let [grouped (group-by (comp #(get % task-name) :hash-group) (mapcat :leaves (:onyx.core/compressed event)))
                  scattered (get grouped nil)
                  scattered-target (rand-nth active-peers)
                  scattered-link (operation/peer-link event scattered-target :send-peer-site)]
              (onyx.extensions/send-messages messenger event scattered-link (compress (filter-by-route scattered task-name)))

              (doseq [k (filter identity (keys grouped))]
                (let [messages (get grouped k)
                      target (nth active-peers (mod (.hashCode k) (count active-peers)))
                      target-link (operation/peer-link event target :send-peer-site)]
                  (onyx.extensions/send-messages messenger event target-link (compress (filter-by-route messages task-name)))))))))
      {})
    {}))

