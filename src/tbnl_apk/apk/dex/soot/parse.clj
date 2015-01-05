(ns tbnl-apk.apk.dex.soot.parse
  ;; internal libs
  (:require [tbnl-apk.util
             :refer [print-stack-trace-if-verbose]])  
  (:require [tbnl-apk.apk.dex.soot.util
             :refer [with-silence mute unmute]])
  (:require [tbnl-apk.apk.dex.soot.helper
             :as helper
             :refer :all])
  (:require [tbnl-apk.apk.dex.soot.emulator
             :as emulator
             :refer :all])  
  (:require [tbnl-apk.apk.parse
             :as apk-parse])
  
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]])  
  ;; special lib
  (:require [me.raynes.fs :as fs])  
  ;; imports
  (:import (soot Unit
                 SootField
                 SootClass
                 SootMethod
                 SootMethodRef)
           (soot.jimple Stmt)
           (soot.options Options)))

;;; declaration

;; func

(declare parse-apk
         get-app-comp-interesting-invokes)

;;; implementation

(defn parse-apk
  "parse apk with soot"
  [apk-name options]
  (merge (apk-parse/parse-apk apk-name)
         {:dex (get-app-comp-interesting-invokes apk-name options)}))

(defn get-app-comp-interesting-invokes
  "get App components and their (transitive) interesting invokes"
  [apk-name
   {:keys [soot-android-jar-path
           verbose
           soot-no-implicit-cf ; do NOT resolve non-invoke inter-procedural control flow, e.g., Java reflection, Android API
           interesting-class-name-patterns
           implicit-cf]
    :as options
    :or {interesting-class-name-patterns [#"^android\."
                                          #"^com\.android\."
                                          #"^dalvik\."
                                          #"^java\.lang\.System"
                                          #"^java\.lang\.Class"
                                          #"^java\.lang\.ClassLoader"
                                          #"^java\.lang\.reflect"
                                          #"^java\.security"]
         implicit-cf {"java.lang.Thread" #{"start"}
                      "java.lang.Runnable" #{"run"}
                      "java.util.concurrent.Callable" #{"call"}
                      "java.util.concurrent.Executor" #{"execute"}
                      "java.util.concurrent.ExecutorService" #{"invokeAny"
                                                               "invokeAll"
                                                               "submit"}
                      "java.lang.reflect.Method" #{"invoke"}
                      "android.os.Handler" #{"post" "postAtFrontOfQueue"
                                             "postAtTime" "postDelayed"}
                      "android.content.Context" #{"startActivity" "startActivities"
                                                  "startService" "stopService"
                                                  "bindService" "unbindService"
                                                  "sendBroadcast" "sendBrocastAsUser"
                                                  "sendOrderedBroadcast" "sendOrderedBroadcastAsUser"
                                                  "sendStickyBroadcast" "sendStickyBroadcastAsUser"
                                                  "registerComponentCallbacks"
                                                  "registerReceiver"}}}}]
  (when (and apk-name (fs/readable? apk-name))
    (let [apk-path (.getPath (io/file apk-name))
          
          
          get-android-jar-path #(let [res-name "EMPTY"
                                      ;; hack to get "tbnl-apk.jar" dir
                                      [_ path] (re-find (re-pattern (str "^file:(.*)/[^/]+!/"
                                                                         res-name "$"))
                                                        (.getPath (io/resource res-name)))]
                                  (str/join (System/getProperty "file.separator")
                                            [path "android.jar"]))
          android-jar-path (if soot-android-jar-path
                             soot-android-jar-path
                             (get-android-jar-path))
          android-api? (fn [name] (some #(re-find % name)
                                        [#"^android\."
                                         #"^com\.android\."
                                         #"^dalvik\."]))
          result (atom {})
          ;; the current thread's Soot context
          g-objgetter (new-g-objgetter)]
      ;; unfortunately, Singleton is so deeply embedded in Soot's implementation, we have to work in critical Section altogether
      (with-soot
        ;; use the current thread's Soot context
        g-objgetter
        ;; reset at the end to release the Soot Objects built up during the analysis
        true
        ;; the real work begins from here
        (when (or (not verbose)
                  (<= verbose 1))
          (mute))
        (try
          (doto options
            (.set_src_prec (Options/src_prec_apk))
            (.set_process_dir [apk-path])
            (.set_force_android_jar android-jar-path)
            
            (.set_allow_phantom_refs true)
            (.set_no_bodies_for_excluded true)
            (.set_ignore_resolution_errors true)
            
            (.set_whole_program true)
            (.set_output_format (Options/output_format_none)))
          (doto phase-options)
          ;; do it manually --- barebone
          (run-body-packs :scene scene
                                 :pack-manager pack-manager
                                 :body-packs ["jb"]
                                 :verbose verbose)
          
          (when (and verbose (> verbose 3))
            (println "body pack finished"))
          
          ;; start working on the bodies
          (let [step1 (fn []
                        (let [application-classes (get-application-classes scene)
                              android-api-descendants (->> application-classes
                                                           (filter (fn [class]
                                                                     (->> (get-transitive-super-class-and-interface class)
                                                                          (filter #(->> (.getName %)
                                                                                        android-api?))
                                                                          not-empty))))
                              android-api-descendant-callbacks  (->> android-api-descendants
                                                                     (remove #(.. ^SootClass % isPhantom))
                                                                     (mapcat #(->> (.. ^SootClass % getMethods)
                                                                                   (filter (fn [method]
                                                                                             (and (.hasActiveBody method)
                                                                                                  (re-find #"^on[A-Z]"
                                                                                                           (.getName method)))))))
                                                                     set)]
                          ;; descendant relations
                          (doseq [descendant android-api-descendants]
                            
                            (when (and verbose (> verbose 3))
                              (println "descendant" descendant))
                            
                            (swap! result assoc-in [(.. descendant getPackageName) (.. descendant getName)]
                                   
                                   {:android-api-ancestors (->> (for [super (get-transitive-super-class-and-interface descendant)
                                                                      :when (->> (.getName super) android-api?)]
                                                                  {:class (.. super getName)
                                                                   :package (.. super getPackageName)})
                                                                set)}))
                          ;; cg will only see parts reachable from these entry points
                          (.. scene
                              (setEntryPoints (seq android-api-descendant-callbacks)))
                          ;; return the result
                          {:android-api-descendants android-api-descendants
                           :android-api-descendant-callbacks android-api-descendant-callbacks}))
                step1-result (step1)
                ;; step 2
                step2 (fn [{:keys [android-api-descendants android-api-descendant-callbacks] :as prev-step-result}]
                        (let [cg (get-cg scene)
                              application-classes (get-application-classes scene)
                              application-methods (get-application-methods scene)

                              interesting-methodref? (memoize
                                                      (fn [^SootMethodRef methodref]
                                                        (let [method-name (.. methodref name)
                                                              class (.. methodref declaringClass)
                                                              class-name (.. class getName)]
                                                          (and true
                                                               ;; only non-application class behaviors are interesting
                                                               (or (.. class isPhantom)
                                                                   (.. methodref resolve isPhantom)
                                                                   (not (contains? application-classes
                                                                                   class)))
                                                               ;; interesting class name patterns
                                                               (some #(re-find % class-name)
                                                                     interesting-class-name-patterns)
                                                               ;; <init> is not interesting
                                                               (not (re-find #"<[^>]+>" method-name))))))]
                          
                          (doseq [callback android-api-descendant-callbacks]
                            (let [callback-class (.. callback getDeclaringClass)]
                              
                              (when (and verbose (> verbose 3))
                                (println "callback" callback))

                              (let [interesting-methodrefs (atom #{})
                                    implicit-cf-invoke-methods (atom #{})]
                                
                                (let [visited (atom #{})]
                                  (process-worklist
                                   ;; the initial worklist
                                   #{callback}

                                   ;; the process
                                   (fn [worklist]
                                     (let [t1  (set/union worklist
                                                          (mapcat-invoke-methods worklist)
                                                          (mapcat-edgeout-methods worklist cg))
                                           t2 (set/difference t1
                                                              @visited)
                                           all (set/intersection t2 application-methods)]
                                       (swap! interesting-methodrefs into
                                              (->> all
                                                   mapcat-invoke-methodrefs
                                                   (filter-interesting-methodrefs interesting-methodref?)))
                                       (when-not soot-no-implicit-cf
                                         (swap! implicit-cf-invoke-methods into
                                                (->> all
                                                     (filter-implicit-cf-invoke-methods implicit-cf))))
                                       (swap! visited set/union worklist)
                                       ;; the new worklist
                                       (set/difference all
                                                       worklist)))))

                                ;; explicit cf
                                (swap! result assoc-in
                                       [(.. callback-class getPackageName)
                                        (.. callback-class getName)
                                        :callbacks
                                        (.getName callback)
                                        :explicit]
                                       (->> @interesting-methodrefs
                                            (map #(let [methodref ^SootMethodRef %
                                                        class (.. methodref declaringClass)]
                                                    {:method (.. methodref name)
                                                     :class (.. class getName)
                                                     :package (.. class getPackageName)}))
                                            set))
                                ;; implicit cf
                                (try
                                  (doseq [^SootMethod method @implicit-cf-invoke-methods]
                                    (let [invokes (get-method-implicit-cf implicit-cf
                                                                          method
                                                                          options)
                                          path [(.. callback-class getPackageName)
                                                (.. callback-class getName)
                                                :callbacks
                                                (.getName callback)
                                                :implicit]]
                                      (when (and verbose
                                                 (> verbose 2))
                                        (pprint {:method method
                                                 :implicit-cf-invokes invokes}))
                                      (doseq [invoke invokes]
                                        ;; make invoke briefer
                                        
                                        (let [methodref (first invoke)
                                              method-name (.. methodref name)
                                              method-class-name (.. methodref declaringClass getName)
                                              root-class-name (first (get-implicit-cf-root-class-names implicit-cf
                                                                                                       methodref))
                                              x [root-class-name method-name]]
                                          (let [invoke (walk/prewalk
                                                        (fn [form]
                                                          (cond
                                                            (instance? soot.SootMethodRef form)
                                                            [(.. form declaringClass getName)
                                                             (.. form name)]

                                                            (instance? soot.SootClass form)
                                                            (.. form getName)

                                                            (instance? soot.RefType form)
                                                            (.. form getClassName)

                                                            (and (list? form)
                                                                 (= (first form) :invoke))
                                                            (->> form second second)

                                                            :default form))
                                                        invoke)
                                                base (second invoke)
                                                args (nth invoke 2)]
                                            (let [update-result
                                                  (fn [& {:keys [category type method instance]}]
                                                    (swap! result update-in (conj path category)
                                                           conj
                                                           {:type type
                                                            :method method
                                                            :instance instance}))]
                                              (cond
                                                (#{["java.lang.Thread" "start"]
                                                   ["java.lang.Runnable" "run"]}
                                                 x)
                                                (update-result :category :task
                                                               :type (first x)
                                                               :method "run"
                                                               :instance (with-out-str (pr base)))
                                                
                                                
                                                (#{["java.util.concurrent.Callable" "call"]}
                                                 x)
                                                (update-result :category :task
                                                               :type (first x)
                                                               :method "call"
                                                               :instance (with-out-str (pr base)))

                                                (#{["java.util.concurrent.ExecutorService" "invokeAny"]
                                                   ["java.util.concurrent.ExecutorService" "invokeAll"]
                                                   ["java.util.concurrent.ExecutorService" "submit"]}
                                                 x)
                                                (update-result :category :task
                                                               :type (first x)
                                                               :method "call"
                                                               :instance (with-out-str (pr (first args))))
                                                
                                                (#{["java.util.concurrent.Executor" "execute"]
                                                   ["android.os.Handler" "post"]
                                                   ["android.os.Handler" "postAtFrontOfQueue"]
                                                   ["android.os.Handler" "postAtTime"]
                                                   ["android.os.Handler" "postDelayed"]}
                                                 x)
                                                (update-result :category :task
                                                               :type (first x)
                                                               :class method-class-name
                                                               :method "run"
                                                               :instance (with-out-str (pr (first args)))) 

                                                (#{["java.lang.reflect.Method" "invoke"]}
                                                 x)
                                                (update-result :category :task
                                                               :type (first x)
                                                               :method (with-out-str (prn base))
                                                               :instance (with-out-str (pr (list base
                                                                                                 (first args)
                                                                                                 (rest args)))))
                                                (#{["android.content.Context" "startActivity"]
                                                   ["android.content.Context" "startActivities"]}
                                                 x)
                                                (update-result :category :component
                                                               :type "android.app.Activity"
                                                               :instance (with-out-str (pr (first args))))

                                                (#{["android.content.Context" "startService"]
                                                   ["android.content.Context" "stopService"]
                                                   ["android.content.Context" "bindService"]
                                                   ["android.content.Context" "unbindService"]}
                                                 x)
                                                (update-result :category :component
                                                               :type "android.app.Service"
                                                               :instance (with-out-str (pr (first args))))

                                                (#{["android.content.Context" "sendBroadcast"]
                                                   ["android.content.Context" "sendBrocastAsUser"]
                                                   ["android.content.Context" "sendOrderedBroadcast"]
                                                   ["android.content.Context" "sendOrderedBroadcastAsUser"]
                                                   ["android.content.Context" "sendStickyBroadcast"]
                                                   ["android.content.Context" "sendStickyBroadcastAsUser"]}
                                                 x)
                                                (update-result :category :component
                                                               :type "android.content.BroadcastReceiver"
                                                               :instance (with-out-str (pr (first args))))

                                                (#{["android.content.Context" "registerComponentCallbacks"]}
                                                 x)
                                                (update-result :category :component
                                                               :type "android.content.ComponentCallbacks"
                                                               :instance (with-out-str (pr (first args))))

                                                (#{["android.content.Context" "registerReceiver"]}
                                                 x)
                                                (update-result :category :component
                                                               :type "android.content.BroadcastReceiver"
                                                               :instance (with-out-str (pr args))))))))
                                      ;; make :implicit part from a seq to a set
                                      (doseq [category (get-in result path)]
                                        (swap! result update-in (conj path category)
                                               set))))
                                  (catch Exception e
                                    (print-stack-trace-if-verbose e verbose)))
                                
                                ;; step 2 result
                                {:interesting-methodrefs @interesting-methodrefs
                                 :implicit-cf-invoke-methods @implicit-cf-invoke-methods})))))
                step2-result (step2 step1-result)])
          ;; catch Exception to prevent disrupting outer threads
          (catch Exception e
            (print-stack-trace-if-verbose e verbose))
          (finally
            (unmute))))
      ;; for debug
      (when (and verbose (> verbose 2))
        (pprint @result))
      @result)))
















