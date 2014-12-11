(ns tbnl-apk.apk.dex.opcodes
  (require [clojure.string :as str]
           [clojure.set :as set])
  (import (org.ow2.asmdex Opcodes)))

(let [opcodes-access {:abstract Opcodes/ACC_ABSTRACT
                      :annotation Opcodes/ACC_ANNOTATION
                      :bridge Opcodes/ACC_BRIDGE
                      :constructor Opcodes/ACC_CONSTRUCTOR
                      :declared-synchronized Opcodes/ACC_DECLARED_SYNCHRONIZED
                      :enum Opcodes/ACC_ENUM
                      :final Opcodes/ACC_FINAL
                      :interface Opcodes/ACC_INTERFACE
                      :native Opcodes/ACC_NATIVE
                      :private Opcodes/ACC_PRIVATE
                      :protectdd Opcodes/ACC_PROTECTED
                      :public Opcodes/ACC_PUBLIC
                      :static Opcodes/ACC_STATIC
                      :strict Opcodes/ACC_STRICT
                      :synthetic Opcodes/ACC_SYNTHETIC
                      :transient Opcodes/ACC_TRANSIENT
                      :unknown Opcodes/ACC_UNKNOWN
                      :varargs Opcodes/ACC_VARARGS
                      :volatile Opcodes/ACC_VOLATILE}]
  (defn decode-opcodes-access [code]
    (into #{}
          (filter #(not= 0
                         (bit-and code
                                  (get opcodes-access % 0)))
                  (keys opcodes-access))))
  (defn encode-opcodes-access [access]
    (reduce bit-or 0 (map opcodes-access
                          (set/intersection (into #{} access)
                                            (into #{}
                                                  (keys opcodes-access)))))))

