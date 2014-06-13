(ns riemann.jvm-profiler
  (:require [riemann.jvm-profiler.stack :as stack]
            [riemann.client.http :as client])
  (:import (java.lang StackTraceElement)))

(def http-client client/client)
(def localhost client/localhost)

(def tags ["jvm" "profile"])

(defn report
  "Send information about stack statistics to Riemann."
  [client dt host prefix stats]
  (let [{:keys [rate sample]} stats]
    (->> sample
         stack/hotspots
         (map (fn [[^StackTraceElement frame
                    {:keys [self-time top-trace top-trace-time]}]]
                {:host        (or host (client/localhost))
                 :service     (str prefix "profiler fn "
                                   (.getClassName frame) " "
                                   (.getMethodName frame))
                 :file        (.getFileName frame)
                 :line        (.getLineNumber frame)
                 :description (stack/trace->string top-trace)
                 :state       "ok"
                 :metric      self-time
                 :ttl         (* 2 dt)
                 :tags        tags}))
         (concat [{:host    host
                   :service (str prefix "profiler rate")
                   :state   "ok"
                   :metric  rate
                   :ttl     (* 2 dt)
                   :tags    tags}])
         (client/send-events client))))

(defn start
  "Starts profiling, sending events to the given Riemann client. Call (stop!)
  with start's return value to stop profiling. Options:

  :client     Riemann HTTP client to send events to. Right now, Because Of
              Reasons (hadoop), we only support our own built-in client
              via (http-client). I'd like to have a runtime check for
              riemann-clojure-client and support its interfaces as well, but
              we can't add the official client as a dep to the jar without
              breaking Hadoop. Shoot me a PR?
  :host       Used to construct a client if no client is given.
  :port       Riemann HTTP port.
  :prefix     Service prefix for distinguishing this telemetry from other apps
              (default \"\")
  :localhost  Override the hostname (default: nil; calls (localhost))
  :dt         How often to send telemetry events to Riemann, in seconds
              (default 5)
  :load       Target fraction of one core's CPU time to use for profiling
              (default 0.02)"
  [opts]
  (assert :client opts)
  (let [dt     (or (:dt opts) 5)
        client (or (:client opts)
                   (http-client (select-keys opts [:host :port])))]
    (stack/start (or (:load opts) 0.02)
                 dt
                 (partial report client
                          dt
                          (:localhost opts)
                          (or (:prefix opts) "")))))

(defn stop!
  "Stop profiling."
  [process]
  (stack/stop! process))

(defonce global
  (atom nil))

(defn start-global!
  "Shortcut for programs that only want to start a single instance of a
  profiler. Calls (start) once, and ignores successive invocations, even
  through reloads. Returns the global profiler."
  [& args]
  (or @global
      (locking global
        (reset! global (apply start args)))))

(defn stop-global!
  "Stop the global profiler."
  []
  (locking global
    (stop! @global)
    (reset! global nil)))
