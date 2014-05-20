(ns riemann.client.http
  "Dirty hack because we can't use protobufs in hadoop; CDH version conflict :("
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            clj-time.format
            clj-time.coerce))

(defn client
  "Constructs a new HTTP client."
  [opts]
  (assert (:host opts))
  {:uri (str "http://" (:host opts) ":" (or (:port opts) 5556) "/events")})

(defn ensure-time
  "Make sure events have a time."
  [event]
  (if (:time event)
    event
    (assoc event :time (long (/ (System/currentTimeMillis) 1000)))))

(defn unix->iso8601
  "Transforms unix time to iso8601 string"
  [unix]
  (clj-time.format/unparse (clj-time.format/formatters :date-time)
                           (clj-time.coerce/from-long (long (* 1000 unix)))))

(defn event->json
  [event]
  (json/generate-string
    (assoc event :time (unix->iso8601 (:time event)))))

(defn send-events
  "Send some events over a client, synchronously"
  [client events]
  (let [events (->> events
                    (map ensure-time)
                    (map event->json))]
    @(http/put (:uri client)
               {:body (str/join "\n" (conj events "\n"))})))
