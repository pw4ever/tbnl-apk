(ns tbnl-apk.core
  ;; internal libs
  (require [tbnl-apk.apk.dex.parse
            :refer [extract-the-dex-in-apk get-the-dex-sha256-digest
                    parse-the-dex-in-apk]]
           [tbnl-apk.apk.aapt.parse
            :refer [get-badging get-manifest]]
           [tbnl-apk.apk.util
            :refer [get-apk-cert-sha256-digest]])
  
  ;; common libs
  (require [clojure.string :as str]
           [clojure.set :as set]
           [clojure.walk :as walk]
           [clojure.zip :as zip]
           [clojure.java.io :as io])
  ;; special libs
  (require [clojure.tools.cli :refer [parse-opts]]
           [clojure.pprint :refer [pprint print-table]])
  (require [me.raynes.fs :as fs])

  ;; imports

  ;; config
  (:gen-class))

;; cli-options example
;; (def cli-options
;;   ;; An option with a required argument
;;   [["-p" "--port PORT" "Port number"
;;     :default 80
;;     :parse-fn #(Integer/parseInt %)
;;     :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
;;    ;; A non-idempotent option
;;    ["-v" nil "Verbosity level"
;;     :id :verbosity
;;     :default 0
;;     :assoc-fn (fn [m k _] (update-in m [k] inc))]
;;    ;; A boolean option defaulting to nil
;;    ["-h" "--help"]])

(def cli-options
  [["-a" "--apk APK" "the input APK file"]
   ["-h" "--help"]])

(defn -main
  "main entry"
  [& args]
  (let [raw (parse-opts args cli-options)
        options (:options raw)
        summary (:summary raw)
        errors (:errors raw)]
    (if (:help options)
      (println summary)
      (let [apk (:apk options)]
        (when (fs/readable? apk)
          ;;(println "sha256 of classes.dex:" (get-the-dex-sha256-digest apk))
          (pprint (get-manifest apk))
          (println (get-apk-cert-sha256-digest apk))
          ;;(extract-the-dex-in-apk apk "extracted-classes.dex")
          ))))
  ;; to ensure it quits
  (shutdown-agents))
