(ns tbnl-apk.apk.dex.soot.util
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]])  
  ;; imports
  (:import (java.io PrintStream
                    OutputStream))
  (:import (soot G)))

;;; declaration
(declare mute unmute
         with-silence)

;;; implementation
(def mutter (PrintStream. (proxy [OutputStream] []
                            (write [_ _1 _2]))))
(def original-system-out System/out)

(defmacro with-silence
  "execute the body in silence"
  [& body]
  `(try
     (mute)
     ~@body
     (finally
       (unmute))))

(defn mute
  "no output"
  []
  (set! (. (G/v) out) mutter)
  (System/setOut mutter))

(defn unmute
  "allow output again"
  []
  (System/setOut original-system-out)
  (set! (. (G/v) out) original-system-out))



