(ns riemann.client.http
  "Dirty hack because we can't use protobufs in hadoop; CDH version conflict :("
  (:require [org.httpkit.client :as http]
            [clojure.java.shell :as sh]
            [cheshire.core :as json]
            [clojure.string :as str]
            clj-time.format
            clj-time.coerce))

(def hostname-refresh-interval
  "How often to allow shelling out to hostname (1), in seconds."
  60)

(defn get-hostname
  "Fetches the hostname by shelling out to hostname (1), whenever the given age
  is stale enough. If the given age is recent, as defined by
  hostname-refresh-interval, returns age and val instead."
  [[age val]]
  (if (and val (<= (* 1000 hostname-refresh-interval)
                   (- (System/currentTimeMillis) age)))
    [age val]
    [(System/currentTimeMillis)
     (let [{:keys [exit out]} (sh/sh "hostname")]
       (if (= exit 0)
         (.trim out)))]))

(let [cache (atom [nil nil])]
  (defn localhost
    "Returns the local host name."
    []
    (if (re-find #"^Windows" (System/getProperty "os.name"))
      (or (System/getenv "COMPUTERNAME") "localhost")
      (or (System/getenv "HOSTNAME")
          (second (swap! cache get-hostname))
          "localhost"))))

(defn client
  "Constructs a new HTTP client."
  [opts]
  (assert (:host opts))
  {:uri (str "http://" (:host opts) ":" (or (:port opts) 5556) "/events")})

(defn defaults
  "Fill in default time and host."
  [event]
  (let [e (transient event)
        e (if (contains? event :time)
            e
            (assoc! e :time (long (/ (System/currentTimeMillis) 1000))))
        e (if (contains? event :host)
            e
            (assoc! e :host (localhost)))]
    (persistent! e)))

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
                    (map defaults)
                    (map event->json))]
    @(http/put (:uri client)
               {:body (str/join "\n" (conj events "\n"))})))
