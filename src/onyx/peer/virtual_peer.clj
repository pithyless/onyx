(ns ^:no-doc onyx.peer.virtual-peer
  (:require [clojure.core.async :refer [chan alts!! >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [dire.core :as dire]
            [onyx.extensions :as extensions]
            [onyx.peer.task-lifecycle :refer [task-lifecycle]]))

(defn payload-loop [id sync queue payload-ch shutdown-ch status-ch dead-ch pulse fn-params]
  (let [complete-ch (chan 1)]
    (loop [pipeline nil]
      (when-let [[v ch] (alts!! [shutdown-ch complete-ch payload-ch] :priority true)]
        (when-not (nil? pipeline)
          (component/stop pipeline))

        (cond (nil? v) (extensions/delete sync pulse)
              (= ch complete-ch) (recur nil)
              (= ch payload-ch)
              (let [payload-node (:path v)
                    payload (extensions/read-place sync payload-node)
                    status-ch (chan 1)]

                (extensions/on-change sync (:status (:nodes payload)) #(>!! status-ch %))
                (extensions/touch-place sync (:ack (:nodes payload)))

                (<!! status-ch)

                (let [new-pipeline (task-lifecycle id payload sync queue payload-ch complete-ch fn-params)]
                  (recur (component/start new-pipeline))))
              :else (recur nil))))
    (>!! dead-ch true)))

(defrecord VirtualPeer [fn-params]
  component/Lifecycle

  (start [{:keys [sync queue] :as component}]
    (let [id (java.util.UUID/randomUUID)]
      (taoensso.timbre/info (format "Starting Virtual Peer %s" id))

      (let [peer (extensions/create sync :peer)
            payload (extensions/create sync :payload)
            pulse (extensions/create sync :pulse)
            shutdown (extensions/create sync :shutdown)

            payload-ch (chan 1)
            shutdown-ch (chan 1)
            status-ch (chan 1)
            dead-ch (chan)]
        
        (extensions/write-place sync peer {:id id :pulse pulse :shutdown shutdown :payload payload})
        (extensions/write-place sync pulse {:id id})
        (extensions/write-place sync shutdown {:id id})
        (extensions/on-change sync payload #(>!! payload-ch %))

        (dire/with-handler! #'payload-loop
          java.lang.Exception
          (fn [e & _] (timbre/info e)))

        (assoc component
          :id id
          
          :peer-node peer
          :payload-node payload
          :pulse-node pulse
          :shutdown-node shutdown
          
          :payload-ch payload-ch
          :shutdown-ch shutdown-ch
          :status-ch status-ch
          :dead-ch dead-ch

          :payload-thread (future (payload-loop id sync queue payload-ch shutdown-ch status-ch dead-ch pulse fn-params))))))

  (stop [component]
    (taoensso.timbre/info (format "Stopping Virtual Peer %s" (:id component)))

    (close! (:payload-ch component))
    (close! (:shutdown-ch component))
    (close! (:status-ch component))

    (<!! (:dead-ch component))
    
    component))

(defn virtual-peer [fn-params]
  (map->VirtualPeer {:fn-params fn-params}))

