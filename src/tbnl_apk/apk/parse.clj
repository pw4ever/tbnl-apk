(ns tbnl-apk.apk.parse
  ;; internal libs
  (:require [tbnl-apk.apk.dex.parse
             :refer [get-the-dex-sha256-digest]]           
            [tbnl-apk.apk.aapt.parse
             :refer [get-manifest]]
            [tbnl-apk.apk.util
             :refer [get-apk-cert-sha256-digest get-file-sha256-digest]])  
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]]))

;;; declaration
(declare parse-apk)

;;; implementation

(defn parse-apk
  "parse apk: the common part"
  [apk-name]
  {:manifest (get-manifest apk-name)
   :dex-sha256 (get-the-dex-sha256-digest apk-name)
   :cert-sha256 (get-apk-cert-sha256-digest apk-name)
   :sha256 (get-file-sha256-digest apk-name)})
