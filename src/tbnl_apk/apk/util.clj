(ns tbnl-apk.apk.util
  ;; internal libs
  ;; common libs
  (require [clojure.string :as str]
           [clojure.set :as set]
           [clojure.walk :as walk]
           [clojure.zip :as zip]
           [clojure.java.io :as io])
  ;; special libs
  (require [pandect.algo.sha256 :refer [sha256-bytes sha256]])
  (require [clojure.java.shell :refer [sh]])
  
  ;; imports
  ;; http://stackoverflow.com/a/1802126
  (import (java.io ByteArrayInputStream))
  
  ;; http://stackoverflow.com/a/5419767
  (import (java.util.zip ZipFile
                         ZipInputStream
                         ZipEntry))
  
  ;; http://stackoverflow.com/a/19194580
  (import (java.nio.file Files
                         Paths
                         StandardCopyOption))   
  
  ;; http://stackoverflow.com/a/1264756
  (import (org.apache.commons.io IOUtils)))

(declare get-apk-file-bytes get-apk-file-input-stream
         extract-apk-file
         get-apk-file-sha256-digest get-apk-cert-sha256-digest)

(defn ^bytes get-apk-file-bytes [apk file-name]
  "get bytes of file-name in apk"
  (with-open [apk (ZipFile. ^String apk)]
    (IOUtils/toByteArray ^java.io.InputStream (.getInputStream apk (.getEntry apk file-name)))))

(defn ^java.io.InputStream get-apk-file-input-stream [apk file-name]
  "get an input stream of file-name in apk"
  (ByteArrayInputStream. (get-apk-file-bytes apk file-name)))

(defn extract-apk-file [apk file-name output-file-name]
  "extract file-name in apk to output-file-name"
  (Files/copy ^java.io.InputStream (get-apk-file-input-stream apk file-name)
              (Paths/get output-file-name (into-array String [""]))
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn get-apk-file-sha256-digest [apk file-name]
  "get sha256 digest of file-name in apk"
  (sha256 (get-apk-file-bytes apk file-name)))

(defn get-apk-cert-sha256-digest [apk]
  "get sha256 digest of apk's cert"
  (let [raw (:out (sh "keytool" "-printcert" "-jarfile"
                      apk))
        [_ digest] (re-find #"SHA256:\s+(\S+)" raw)]
    digest))



















