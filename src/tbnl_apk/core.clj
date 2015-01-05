(ns tbnl-apk.core
  ;; internal libs
  
  (:require [tbnl-apk.util
             :refer [print-stack-trace-if-verbose]]
            [tbnl-apk.apk.parse
             :as apk-parse]
            [tbnl-apk.apk.aapt.parse
             :as aapt-parse]
            [tbnl-apk.neo4j.core
             :as neo4j]
            [tbnl-apk.apk.dex.soot.parse
             :as soot-parse])
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]])
  ;; special libs
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [clojure.java.shell :refer [sh]])
  (:require [clojure.core.async :as async :refer [thread]])
  (:require [me.raynes.fs :as fs])
  (:require [clojure.tools.nrepl.server :refer [start-server stop-server]])
  (:require [cider.nrepl :refer [cider-nrepl-handler]])
  ;; imports
  (:import (java.util.concurrent Executors
                                 TimeUnit))
  ;; config
  (:gen-class))

(def cli-options
  [
   ;; general options
   ["-h" "--help" "you are reading it now"]
   ["-v" "--verbose" "be verbose (more \"v\" for more verbosity)"
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ["-i" "--interactive" "do not exit (i.e., shutdown-agents) at the end"]

   ;; does not work with Soot
   #_["-j" "--jobs JOBS"
    "number of parallel jobs (default: #core+2; NB Soot processing is serialized due to its Singleton design)"
    :parse-fn #(Integer/parseInt %)
    :default  (+ (.. Runtime getRuntime availableProcessors)
                 2)
    :validate [#(> % 0)
               (format "You need at least 1 job to proceed")]]

   ;; nREPL config
   [nil "--nrepl-port PORT" "REPL port"
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000)
               (format "Must be a number between 0 and %d (exclusively)"
                       0x10000)]]   

   ;; Soot config
   ["-s" "--soot-task-build-model" "build APK model with Soot"]
   [nil "--soot-android-jar-path" "path of android.jar for Soot's Dexpler"]
   [nil "--soot-no-implicit-cf" "do not detect implicit control flows"]

   ;; Neo4j config
   [nil "--neo4j-port PORT" "Neo4j server port"
    :parse-fn #(Integer/parseInt %)
    :default 7474
    :validate [#(< 0 % 0x10000)
               (format "Must be a number between 0 and %d (exclusively)"
                       0x10000)]]
   [nil "--neo4j-protocol PROTOCOL" "Neo4j server protocol (http/https)"
    :default "http"]

   ;; Neo4j tasks
   ["-n" "--neo4j-task-populate" "populate Neo4j with APK model"]
   ["-t" "--neo4j-task-tag" "tag Neo4j Apk nodes with labels"]
   ["-T" "--neo4j-task-untag" "untag Neo4j Apk nodes with labels"]
   
   ;; misc tasks
   ["-d" "--dump-manifest" "dump AndroidManifest.xml"]

   ])

(def main-options
  "for consumption by nREPL session"
  (atom nil))

(def mutex
  "establish critical section"
  (Object.))

(def completed-task-counter
  "completed task counter"
  (atom 0))

(defmacro with-mutex-locked
  "synchronize verbose ouput"
  [& body]
  `(locking mutex
     ~@body))

(defn work
  "do the real work on apk"
  [apk-name
   labels
   {:keys [verbose

           soot-task-build-model
           
           neo4j-port neo4j-protocol
           neo4j-task-populate neo4j-task-tag neo4j-task-untag
           
           dump-manifest]
    :as options}]
  (when (and apk-name (fs/readable? apk-name))
    (when (and verbose (> verbose 1))
      (println "processing" apk-name))
    
    (let [start-time (System/currentTimeMillis)]
      (try
        (when dump-manifest
          (print (aapt-parse/get-manifest-xml apk-name))
          (flush))
        
        (when soot-task-build-model
          (let [apk (soot-parse/parse-apk apk-name options)]
            (when neo4j-task-populate
              (neo4j/populate-from-parsed-apk apk
                                              options))))

        (when (or neo4j-task-tag neo4j-task-untag)
          (when (and verbose (> verbose 1))
            (with-mutex-locked
              (println "Neo4j:" (cond
                                  neo4j-task-tag "tag"
                                  neo4j-task-untag "untag")
                       apk-name
                       labels)))
          
          (let [apk (apk-parse/parse-apk apk-name)]
            (cond
              neo4j-task-tag (neo4j/tag-apk apk labels options)
              neo4j-task-untag (neo4j/untag-apk apk labels options))))
        
        (when (and verbose (> verbose 0))
          (with-mutex-locked
            (swap! completed-task-counter inc)
            (println (format "%1$d: %2$s processed in %3$.3f seconds"
                             @completed-task-counter
                             apk-name
                             (/ (- (System/currentTimeMillis) start-time)
                                1000.0)))))
        
        (catch Exception e
          (print-stack-trace-if-verbose e verbose))))))

(defn -main
  "main entry"
  [& args]
  (let [raw (parse-opts args cli-options)
        {:keys [options summary errors]} raw
        {:keys [verbose interactive #_jobs help
                nrepl-port
                neo4j-task-populate]} options]
    (try
      ;; print out error messages if any
      (when errors
        (binding [*out* *err*]
          (doseq [error errors]
            (println error))))
      ;; whether help is requested
      (if help
        (do
          (println "<BUILDINFO>")
          (println summary))
        ;; if help is not requested, do the real work
        (do
          ;; use separate thread to start nREPL, so do not delay other task
          (thread
            (when nrepl-port
              (try
                (start-server :port nrepl-port
                              :handler cider-nrepl-handler)
                (catch Exception e
                  (when (> verbose 1)
                    (binding [*out* *err*]
                      (println "error: nREPL server cannot start at port"
                               nrepl-port)))))))

          (when neo4j-task-populate
            ;; "create index" only need to executed once if populate-neo4j is requested
            (when (> verbose 1)
              (with-mutex-locked
                (println "Neo4j:" "creating index")))
            (neo4j/create-index options)
            (when (> verbose 1)
              (with-mutex-locked
                (println "Neo4j:" "index created"))))

          (loop [line (read-line)]
            (when line
              (let [[apk-name & raw-labels] (str/split line #"\s+")
                    labels (map str/capitalize raw-labels)]
                (try
                  (when (and apk-name (fs/readable? apk-name))
                    ;; do the real work
                    (work apk-name labels options))
                  (catch Exception e
                    (print-stack-trace-if-verbose e verbose)))
                (recur (read-line)))))
          
          (when neo4j-task-populate
            (when (> verbose 1)
              (with-mutex-locked
                (println "Neo4j:" "marking Android API")))
            ;; mark Android API
            (neo4j/mark-android-api options)
            (when (> verbose 1)
              (with-mutex-locked
                (println "Neo4j:" "Android API marked")))))) 
      (when interactive
        ;; block when interactive is requested
        @(promise))
      (catch Exception e
        (print-stack-trace-if-verbose e verbose))
      (finally
        ;; clean-up
        (shutdown-agents)    
        (when (> verbose 1)
          (with-mutex-locked
            (println "shutting down")))
        (System/exit 0)))))
