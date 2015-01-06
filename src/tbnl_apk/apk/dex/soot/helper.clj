(ns tbnl-apk.apk.dex.soot.helper
  ;; internal libs
  (:require [tbnl-apk.util
             :refer [print-stack-trace-if-verbose]])
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]])
  ;; import
  (:import (soot G
                 G$GlobalObjectGetter
                 PhaseOptions
                 PackManager
                 Scene

                 Pack
                 Unit
                 SootField
                 SootClass
                 SootMethod
                 SootMethodRef

                 Local

                 RefLikeType
                 ArrayType
                 RefType)
           (soot.options Options)
           (soot.jimple Stmt
                        StmtSwitch)
           (soot.jimple.toolkits.callgraph CallGraph
                                           Edge)))

;;; declaration

(declare perform-worklist

         with-soot new-g-objgetter
         
         get-application-classes get-application-methods get-method-body map-class-bodies run-body-packs
         
         mapcat-invoke-methodrefs resolve-methodrefs mapcat-invoke-methods
         
         get-cg mapcat-edgeout-methods
         
         get-transitive-super-class-and-interface transitive-ancestor?

         filter-interesting-methodrefs)

;;; implementation

(defn process-worklist
  "process worklist until it is empty

process takes a worklist as input, and outputs the new worklist"
  [initial-worklist process]
  (loop [worklist initial-worklist]
    (when-not (empty? worklist)
      (recur (process worklist)))))


(def soot-mutex
  "Soot mutex: Soot is unfortunately Singleton"
  (Object.))

(def system-security-manager
  "System's exsiting security manager"
  (System/getSecurityManager))

(def noexit-security-manager
  "prevent Soot brining down the system with System.exit"
  ;; http://stackoverflow.com/questions/21029651/security-manager-in-clojure/21033599#21033599
  (proxy [SecurityManager] []
    (checkPermission
      ([^java.security.Permission perm]
       (when (.startsWith (.getName perm) "exitVM")
         (throw (SecurityException. "no exit for Soot"))))
      ([^java.security.Permission perm ^Object context]
       (when (.startsWith (.getName perm) "exitVM")
         (throw (SecurityException. "no exit for Soot")))))))

(defmacro with-soot
  "wrap body with major Soot refs *at the call time*: g, scene, pack-manager, options, phase-options; g can be (optionally) provided with g-objgetter (nil to ask fetch the G *at the call time*); (G/reset) at the end if \"reset?\" is true"
  [g-objgetter reset? & body]
  `(locking soot-mutex
     
     (let [get-transitive-super-class-and-interface#
           (memoize
            (fn [class-or-interface#]
              (let [known# (atom #{})]
                (loop [worklist# #{class-or-interface#}
                       visited# #{}]
                  (when-not (empty? worklist#)
                    (let [new-worklist# (atom #{})]
                      (doseq [item# worklist#
                              :when (not (visited# item#))]
                        (swap! known# conj item#)
                        ;; interfaces
                        (swap! new-worklist# into (->> (.. item# getInterfaces snapshotIterator)
                                                       iterator-seq
                                                       doall))
                        ;; superclass?
                        (when (.. item# hasSuperclass)
                          (swap! new-worklist# conj (.. item# getSuperclass))))
                      (recur (set/difference @new-worklist# worklist#)
                             (set/union visited# worklist#)))))
                @known#)))
           
           transitive-ancestor?#
           (memoize
            (fn [name-or-class-a# class-b#]
              (contains? (->> class-b#
                              get-transitive-super-class-and-interface
                              (map #(.. ^SootClass % getName))
                              set)
                         (if (instance? SootClass name-or-class-a#)
                           (.. name-or-class-a# getName)
                           (str name-or-class-a#)))))
           
           soot-init# (fn []
                        ;; set up memoize functions so that they won't retain objects across
                        (alter-var-root #'get-transitive-super-class-and-interface
                                        (fn [_#] get-transitive-super-class-and-interface#))
                        (alter-var-root #'transitive-ancestor?
                                        (fn [_#] transitive-ancestor?#)))
           
           ;; we have to use this instead of clean# due to the use in ~(when reset? ...)           
           ~'_soot-clean_ (fn []
                            (alter-var-root #'get-transitive-super-class-and-interface
                                            (constantly nil))
                            (alter-var-root #'transitive-ancestor?
                                            (constantly nil))
                            (G/setGlobalObjectGetter nil))]
       (try
         (soot-init#)
         (System/setSecurityManager noexit-security-manager)
         (when (instance? G$GlobalObjectGetter ~g-objgetter)
           (G/setGlobalObjectGetter ~g-objgetter))
         (let [~'g (G/v)
               ~'scene (Scene/v)
               ~'pack-manager (PackManager/v)
               ~'options (Options/v)
               ~'phase-options (PhaseOptions/v)]
           ~@body
           ~(when reset?
              `(~'_soot-clean_)))
         (catch Exception e#
           ;; reset Soot state
           (~'_soot-clean_)
           (throw e#))
         (finally
           (System/setSecurityManager system-security-manager))))))

(defn new-g-objgetter
  "create a new Soot context (G$GlobalObjectGetter)"
  []
  (let [g (new G)]
    (reify G$GlobalObjectGetter
      (getG [this] g)
      (reset [this]))))

(defn get-application-classes
  "get application classes in scene"
  [scene]
  (->> (.. scene getApplicationClasses snapshotIterator)
       iterator-seq
       set
       doall))

(defn get-application-methods
  "get application methods in scene"
  [scene]
  (->> scene
       get-application-classes
       (remove #(.. ^SootClass % isPhantom))
       (mapcat #(.. ^SootClass % getMethods))
       set))

(defn get-method-body
  "get method body"
  [^SootMethod method]
  (if (.. method hasActiveBody)
    (.. method getActiveBody)
    (when (and (not (.. method isPhantom))
               ;; method must have backing source
               (.. method getSource))
      (.. method retrieveActiveBody))))

(defn map-class-bodies
  "map classes to their method bodies"
  [classes]
  (->> classes
       (remove #(.. ^SootClass % isPhantom))
       (mapcat #(->> (.. ^SootClass % getMethods)
                     seq
                     (map get-method-body)
                     (filter identity)))))

(defn run-body-packs
  "run body packs over application classes"
  [& {:keys [scene pack-manager body-packs verbose]}]
  (doto scene
    (.loadNecessaryClasses))
  ;; force application class bodies to be mapped at least once 
  (let [bodies (->> scene get-application-classes map-class-bodies)
        packs (->> body-packs (map #(.. ^PackManager pack-manager (getPack ^String %))))]
    (doseq [^Pack pack packs]
      (when pack
        (doseq [^SootBody body bodies]
          (try
            (.. pack (apply body))
            ;; catch Exception to prevent it destroys outer threads
            (catch Exception e
              (print-stack-trace-if-verbose e verbose))))))))

;; phantom SootClass do not have SootMethod, but we can have SootMethodRef
(defn mapcat-invoke-methodrefs
  "mapcat methods to methodrefs invoked by them"
  [methods]
  (->> methods
       (remove #(.. ^SootMethod % isPhantom))
       ;; try retrieveActiveBody
       (filter #(try
                  (.. ^SootMethod % retrieveActiveBody)
                  true
                  (catch Exception e
                    false)))
       (mapcat #(iterator-seq (.. ^SootMethod % retrieveActiveBody getUnits snapshotIterator)))
       (filter #(.. ^Stmt % containsInvokeExpr))
       (map #(.. ^Stmt % getInvokeExpr getMethodRef))
       doall))

(defn resolve-methodrefs
  "resolve methodrefs"
  [methodrefs]
  (->> methodrefs
       (remove #(.. ^SootMethodRef % declaringClass isPhantom))
       (filter #(try
                  (.. ^SootMethodRef % resolve)
                  true
                  (catch Exception e
                    false)))
       (map #(.. ^SootMethodRef % resolve))))

(defn mapcat-invoke-methods
  "mapcat methods to methods (not methodrefs) invoked by them: for recursive expanding"
  [methods]
  (->> methods
       mapcat-invoke-methodrefs
       set                              ; deduplication early
       resolve-methodrefs
       set))

(defn get-cg
  "get Call Graph from scene"
  [scene]
  (when (.. scene hasCallGraph)
    (.. scene getCallGraph)))

(defn mapcat-edgeout-methods
  "mapcat methods to their edgeout methods on cg "
  [methods cg]
  (when cg
    (->> methods
         (mapcat #(iterator-seq (.. ^CallGraph cg (edgesOutOf %))))
         (map #(.. ^Edge % getTgt))
         set)))

(def get-transitive-super-class-and-interface
  "get transitive super class and interfaces known to Soot"
  nil)

(def transitive-ancestor?
  "name-or-class-a is a transitive ancestor (super class/interface) of class-b"
  nil)

(defn filter-interesting-methodrefs
  "filter interesting methodrefs"
  [interesting-methodref? methodrefs]
  (->> methodrefs
       (filter interesting-methodref?)))
