(ns tbnl-apk.apk.dex.parse
  ;; internal libs
  (require [tbnl-apk.apk.util
            :refer [get-apk-file-bytes get-apk-file-input-stream
                    extract-apk-file
                    get-apk-file-sha256-digest]]
           [tbnl-apk.apk.dex.opcodes
            :refer [decode-opcodes-access
                    encode-opcodes-access]])  
  ;; common libs
  (require [clojure.string :as str]
           [clojure.set :as set]
           [clojure.walk :as walk]
           [clojure.zip :as zip]
           [clojure.java.io :as io])
  ;; special libs
  ;; imports

  ;; http://asm.ow2.org/doc/tutorial-asmdex.html
  (import (org.ow2.asmdex ApplicationReader
                          ApplicationVisitor
                          ClassVisitor
                          AnnotationVisitor
                          FieldVisitor
                          MethodVisitor
                          Opcodes)))

;;; declration

;; var
(declare the-dex)

;; func
(declare extract-the-dex-in-apk get-the-dex-sha256-digest
         parse-the-dex-in-apk)

;; struct
(defrecord DexClass [access name signature super-name interfaces
                     annotations fields methods])
(defrecord DexAnnotation [])
(defrecord DexField [])
(defrecord DexMethod [])


;;; implementation

(def the-dex "classes.dex")

(defn extract-the-dex-in-apk [apk output-file-name]
  "extract the dex in apk to output-file-name"
  (extract-apk-file apk the-dex output-file-name))

(defn get-the-dex-sha256-digest [apk]
  "get sha256 digest of the dex in apk"
  (get-apk-file-sha256-digest apk the-dex))

(defn parse-the-dex-in-apk [apk & {:keys [visit-class
                                          visit-class-annotation
                                          visit-field
                                          visit-method]
                                   :as args}]
  "parse the dex in apk"
  (let [api Opcodes/ASM4
        app-reader (ApplicationReader. api
                                       (get-apk-file-input-stream apk the-dex))
        the-structure (atom {})]
    (let [app-visitor (proxy [ApplicationVisitor] [api]
                        (visitClass [access name signature super-name interfaces]
                          (let [access (decode-opcodes-access access)]
                            (when visit-class
                              (visit-class :access access
                                           :name name
                                           :signature signature
                                           :super-name super-name
                                           :interfaces interfaces))
                            (swap! the-structure assoc-in [name :attrs]
                                   {:access access
                                    :name name
                                    :signature signature
                                    :super-name super-name
                                    :interfaces interfaces})
                            (let [class-name name]
                              (proxy [ClassVisitor] [api]
                                (visitAnnotation [desc visible]
                                  (when visit-class-annotation
                                    (visit-class-annotation :class class-name
                                                            :desc desc
                                                            :visible visible))
                                  (swap! the-structure update-in [class-name :content]
                                         #(conj (vec %1) %2)
                                         {:type :annotation
                                          :class class-name
                                          :desc desc
                                          :visible visible})
                                  nil ; to be extended later
                                  )
                                (visitField [access name desc signature value]
                                  (let [access (decode-opcodes-access access)]
                                    (when visit-field
                                      (visit-field :class class-name
                                                   :access access
                                                   :name name
                                                   :desc desc
                                                   :signature signature
                                                   :value value))
                                    (swap! the-structure update-in [class-name :content]
                                           #(conj (vec %1) %2)
                                           {:type :field
                                            :class class-name
                                            :access access
                                            :name name
                                            :desc desc
                                            :signature signature
                                            :value value})
                                    nil))
                                (visitMethod [access name desc signature exceptions]
                                  (let [access (decode-opcodes-access access)]
                                    (when visit-method
                                      (visit-method :class class-name
                                                    :access access
                                                    :name name
                                                    :desc desc
                                                    :signature signature
                                                    :exceptions exceptions))
                                    (swap! the-structure update-in [class-name :content]
                                           #(conj (vec %1) %2)
                                           {:type :method
                                            :class class-name
                                            :access access
                                            :name name
                                            :desc desc
                                            :signature signature
                                            :exceptions exceptions
                                            })
                                    (let [method-name name]
                                      (proxy [MethodVisitor] [api])))))))))
          ]
      (.accept app-reader
               app-visitor
               (bit-or 0
                       ApplicationReader/SKIP_DEBUG))
      @the-structure)))
