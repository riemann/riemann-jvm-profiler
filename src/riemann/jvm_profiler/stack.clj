(ns riemann.jvm-profiler.stack
  (:require [clj-radix :as radix]
            [interval-metrics.core :as metrics]
            [interval-metrics.measure :as measure]
            [clojure.string :as str]
            [clojure.core.reducers :as r]
            [clojure.tools.logging :refer :all])
  (:import
    [java.lang.reflect
     Array]
    [java.lang.management
     ThreadMXBean
     ManagementFactory
     ThreadInfo]
    [java.lang
     StackTraceElement]))

(set! *warn-on-reflection* true)

(defprotocol IThreadSampler
  (start [_ period])
  (stop [_])
  (stack-counts [_]))

(def ^:private ^ThreadMXBean thread-mx-bean
  (memoize
    (fn []
      (ManagementFactory/getThreadMXBean))))

(defn other-runnable-threads
  "All threads other than the current thread which runnable"
  []
  (let [my-thread-id (.. Thread currentThread getId)]
    (->> (.dumpAllThreads (thread-mx-bean) false false)
         (r/filter (fn [^ThreadInfo thread]
                     (let [state (.getThreadState thread)]
                       (and (identical? state Thread$State/RUNNABLE)
                            (not= my-thread-id (.getThreadId thread)))))))))

(defn stack
  "Given a ThreadInfo, returns a stacktrace as an array of StackTraceElements,
  from top-level to deepest call. Note that this is the opposite order from
  java's Stacktrace representation, which runs from deepest to top. Returns nil
  if no stack is available."
  [^ThreadInfo thread]
  (let [runnable? (identical? Thread$State/RUNNABLE (.getThreadState thread))
        stack (.getStackTrace thread)
        cnt (Array/getLength stack)
        ary (object-array cnt)]
    (when-not (zero? cnt)
      (dotimes [i (dec cnt)]
        (aset ary i (aget stack (int (- cnt i 1)))))
      (aset ary (dec cnt) (aget stack 0))
      ary)))

(defn current-stack-element
  "Given a stacktrace array, returns the currently executing stack element, or
  nil."
  [^objects stack]
  (let [n (count stack)]
    (when (pos? n) (nth stack (dec n)))))

(defn trace->string
  "Formats a stacktrace as a nice string."
  [trace]
  (->> trace
       reverse
       (map (fn [^StackTraceElement frame]
              (str (.getClassName frame) " "
                   (.getMethodName frame) " ("
                   (.getFileName frame) ":"
                   (.getLineNumber frame) ")")))
       (str/join "\n")))

(defn sample
  "A radix tree where the paths are stacktraces (current function last) onto
  the number of threads with that stack, time scale. Excludes current thread's
  stack."
  [scale]
  (->> (other-runnable-threads)
       (reduce (fn [stacks ^ThreadInfo thread]
                 (if-let [stack (stack thread)]
                   (radix/update! stacks stack #(+ (or % 0) scale))
                   stacks))
               (transient (radix/radix-tree)))
       persistent!))

(defn hotspots
  "Takes a radix tree sample and finds the leaf nodes with the highest
  self-time. Returns a sequence of stacktrace elements -> maps of statistics,
  sorted by the estimated time spent in each function. Each map contains

  :time           - The time spent in this function (not including callees)
  :top-trace      - The stacktrace which contributed the most to this function
  :top-trace-time - The time spent in this function thanks to the top trace."
  [sample]
  (->> sample
       (reduce (fn [hotspots [trace time]]
                 (let [element    (current-stack-element trace)
                       cur        (get hotspots element)
                       bigger?    (< (get cur :top-trace-time 0)
                                     time)]
                   (assoc!
                     hotspots element
                     {:self-time      (+ time (get cur :self-time 0))
                      :top-trace-time (if bigger? time (:top-trace-time cur))
                      :top-trace      (if bigger? trace (:top-trace cur))})))
               (transient {}))
       persistent!
       (sort-by (comp :time val))
       reverse
       (take 10)))

(defn new-agg-state
  "New state of an AggSample. Takes a time in nanos."
  [t0]
  {:t0            t0
   :sample        (radix/radix-tree)
   :sample-count  0})

(defrecord AggSample [state]
  metrics/Metric
  (update! [this new-sample]
    (swap! state
           (fn [{:keys [t0 sample sample-count]}]
             {:t0 t0
              :sample-count (inc sample-count)
              :sample       (radix/merge-with + sample new-sample)})))

  metrics/Snapshot
  (snapshot! [this]
    (let [snap @state
          t1   (System/nanoTime)]
      (if (compare-and-set! state snap (new-agg-state t1))
        (let [dt (- t1 (:t0 snap))]
          {:rate         (/ (:sample-count snap) (/ dt 1e9))
           :sample       (->> (:sample snap)
                              (map (fn [[k v]] [k (double (/ v dt))]))
                              (into (:sample snap)))})
          (recur)))))

(defn agg-sample
  "Constructs a new sample metric for aggregating stacktrace information."
  []
  (AggSample. (atom (new-agg-state (System/nanoTime)))))

(defmacro daemon
  "Starts a daemon thread with the given name."
  [name & body]
  `(doto (Thread. (fn ~'body []
                    ~@body))
     (.setName ~name)
     (.setDaemon true)
     (.start)))

(defn sampler!
  "Samples the stack repeatedly, merging results into the given atom. Tries to
  only run for load-target fraction of a thread's time; 0 means never sample, 1
  means run all the time. Returns a promise which, when delivered false,
  stops the sampler."
  [agg-sample target-load]
  (let [running (promise)]
    (daemon "riemann-jvm-profiler sampler"
      (loop [last-t0 (System/nanoTime)]
        (when (deref running 0 true)
          (recur
            (long (try
                    (let [t0 (System/nanoTime)
                          _   (metrics/update! agg-sample
                                               (sample (- t0 last-t0)))
                          t1 (System/nanoTime)]
                      (-> t1
                          (- t0)
                          (/ 1e6 target-load)
                          (max 1)
                          (min 1000)
                          (Thread/sleep))
                      t0)
                    (catch Exception e
                      (warn e "sample-repeatedly error")
                      (Thread/sleep 1000)
                      last-t0)))))))
    running))

(defn reporter!
  "Every dt seconds, snapshots the aggregate sampler, and invokes f with that
  snapshot: a map of

  :rate    Number of samples taken per second
  :sample  Radix tree of stacktraces to dimensionless self-times; the estimated
           number of seconds spent evaluating that stacktrace, per second."
  [agg-sample dt f]
  (let [anchor  (measure/linear-time)
        running (promise)]
    (daemon "riemann-jvm-profiler reporter"
      (loop []
        (when (deref running
                     (* 1000 (- dt (mod (- (measure/linear-time) anchor) dt)))
                     true)
          (try
            (f (metrics/snapshot! agg-sample))
            (catch Exception e
              (warn e "reporter error")))
          (recur))))
    running))

(defn start
  "Start a reporter process which calls f with sample information every dt
  seconds."
  [load-factor dt f]
  (let [agg-sample (agg-sample)]
    {:sampler  (sampler!  agg-sample load-factor)
     :reporter (reporter! agg-sample dt f)}))

(defn stop!
  "Stop running a reporter process"
  [process]
  (deliver (:sampler process) false)
  (deliver (:reporter process) false)
  nil)
