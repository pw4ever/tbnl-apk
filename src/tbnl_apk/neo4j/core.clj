(ns tbnl-apk.neo4j.core
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
  ;; special libs
  (:require [clojurewerkz.neocons.rest :as nr]
            [clojurewerkz.neocons.rest.transaction :as ntx]))

(declare populate-from-parsed-apk 
         tag-apk untag-apk
         create-index mark-android-api
         connect android-api?)

(def defaults (atom {:neo4j-port 7474
                     :neo4j-protocol "http"}))

(defn populate-from-parsed-apk
  "populate the database with the parsed apk structure"
  [apk {:keys []
        :as options}]
  (let [statements (atom [])]
    (let [manifest (:manifest apk)
          dex-sha256 (:dex-sha256 apk)
          cert-sha256 (:cert-sha256 apk)
          apk-sha256 (:sha256 apk)
          apk-package (:package manifest)
          apk-version-name (:android:versionName manifest)
          apk-version-code (:android:versionCode manifest)
          dex (:dex apk)]
      ;; overall structure
      (swap! statements conj
             (ntx/statement
              (str/join " "
                        ["MERGE (signkey:SigningKey {sha256:{certsha256}})"
                         "MERGE (apk:Apk {sha256:{apksha256},package:{apkpackage},versionCode:{apkversioncode},versionName:{apkversionname}})"
                         "MERGE (dex:Dex {sha256:{dexsha256}})"
                         "MERGE (signkey)-[:Sign]->(apk)-[:Contain]->(dex)"
                         "FOREACH ("
                         "perm in {usespermission} |"
                         "  MERGE (n:Permission {name:perm})"
                         "  MERGE (n)<-[:Use]-(apk)"
                         ")"
                         "FOREACH ("
                         "perm in {permission} |"
                         "  MERGE (n:Permission {name:perm})"
                         "  MERGE (n)<-[:Define]-(apk)"
                         ")"])
              {:certsha256 cert-sha256
               :apksha256 apk-sha256
               :dexsha256 dex-sha256
               :usespermission (->> manifest
                                    :uses-permission
                                    (map name)
                                    ;; only consider Android internal API ones
                                    ;;(filter android-api?)
                                    )
               :permission (->> manifest
                                :permission
                                (map name)
                                ;; only consider API ones
                                ;;(filter android-api?)
                                )
               :apkpackage apk-package
               :apkversionname apk-version-name
               :apkversioncode apk-version-code}))
      
      (doseq [package-name (->> dex keys)]
        (doseq [class-name (->> (get-in dex [package-name]) keys)]
          (swap! statements conj
                 (ntx/statement
                  (str/join " "
                            ["MERGE (dex:Dex {sha256:{dexsha256}})"
                             "MERGE (package:Package {name:{packagename}})"
                             "MERGE (class:Class {name:{classname}})"
                             "MERGE (dex)-[:Contain]->(package)-[:Contain]->(class)"
                             "MERGE (dex)-[:Contain]->(class)"])
                  {:dexsha256 dex-sha256
                   :packagename package-name
                   :classname class-name}))
          (let [{:keys [android-api-ancestors callbacks]} (->> (get-in dex [package-name class-name]))]
            (doseq [base android-api-ancestors]
              (let [ancestor-package (:package base)
                    ancestor-class (:class base)]
                (swap! statements conj
                       (ntx/statement
                        (str/join " "
                                  ["MERGE (class:Class {name:{classname}})"
                                   "MERGE (ancestorpackage:AndroidAPI:Package {name:{ancestorpackage}})"
                                   "MERGE (ancestorclass:AndroidAPI:Class {name:{ancestorclass}})"
                                   "MERGE (ancestorpackage)-[:Contain]->(ancestorclass)"
                                   "MERGE (class)-[:Descend]->(ancestorclass)"])
                        {:classname class-name
                         :ancestorpackage ancestor-package
                         :ancestorclass ancestor-class}))))
            (doseq [callback-name (->> callbacks keys)]
              (let [path [package-name class-name :callbacks callback-name]]
                ;; explicit control flow
                (let [path (conj path :explicit)]
                  (doseq [callback-invoke (get-in dex path)]
                    (let [invoke-package-name (:package callback-invoke)
                          invoke-class-name (:class callback-invoke)
                          invoke-name (:method callback-invoke)]
                      (swap! statements conj
                             (ntx/statement
                              (str/join " "
                                        ["MERGE (class:Class {name:{classname}})"
                                         "MERGE (callback:Method:Callback {name:{callbackname}})"
                                         "MERGE (invokepackage:Package {name:{invokepackagename}})"
                                         "MERGE (invokeclass:Class {name:{invokeclassname}})"
                                         "MERGE (invokename:Method {name:{invokename}})"

                                         "MERGE (class)-[:Contain]->(callback)"
                                         "MERGE (invokepackage)-[:Contain]->(invokeclass)-[:Contain]->(invokename)"
                                         "MERGE (callback)-[:ExplicitInvoke]->(invokename)"])
                              {:classname class-name
                               :callbackname (str class-name "." callback-name)
                               :invokepackagename invoke-package-name
                               :invokeclassname invoke-class-name
                               :invokename (str invoke-class-name "." invoke-name)})))))
                ;; implicit control flow
                (let [path (conj path :implicit)]
                  (doseq [category (keys (get-in dex path))]
                    (doseq [implicit-invoke (get-in dex (conj path category))]
                      (let [category (->> category name str/capitalize)
                            type (:type implicit-invoke)
                            method (:method implicit-invoke)
                            instance (:instance implicit-invoke)]
                        (swap! statements conj
                               (ntx/statement
                                (str/join " "
                                          ["MERGE (callback:Method:Callback {name:{callbackname}})"
                                           "MERGE (type:Class {name:{type}})"
                                           (str "MERGE (instance:" category
                                                " {instance:{instance}"
                                                (if method
                                                  ;; must use single quote to avoid quote clash
                                                  (format ",method:'%1$s'})" method)
                                                  "})"))

                                           "MERGE (callback)-[:ImplicitInvoke]->(instance)-[:Descend]->(type)"])
                                {:callbackname (str class-name "." callback-name)
                                 :type type
                                 :instance instance})))))))))))
      
      ;; app components
      (doseq [comp-type [:activity :service :receiver]]
        (doseq [[comp-name {:keys [intent-filter-action
                                   intent-filter-category]}]
                (->> manifest
                     comp-type)]
          (let [comp-name (name comp-name)
                intent-filter-action (map name intent-filter-action)
                intent-filter-category (map name intent-filter-category)]
            (swap! statements conj
                   (ntx/statement
                    (str/join " "
                              ["MERGE (dex:Dex {sha256:{dexsha256}})"
                               "MERGE (ic:Class {name:{compname}})"
                               (format (str/join " "
                                                 ["ON CREATE SET ic:%1$s:Component"
                                                  "ON MATCH SET ic:%1$s:Component"])
                                       (->> comp-type name str/capitalize))
                               "MERGE (dex)-[:Contain]->(ic)"
                               "FOREACH ("
                               "action IN {intentfilteraction} |"
                               "  MERGE (n:IntentFilterAction {name:action})"
                               "  MERGE (n)-[:Trigger]->(ic)"
                               ")"
                               "FOREACH ("
                               "category IN {intentfiltercategory} |"
                               "  MERGE (n:IntentFilterCategory {name:category})"
                               "  MERGE (n)-[:Trigger]->(ic)"
                               ")"
                               ])
                    {:dexsha256 dex-sha256
                     :compname comp-name
                     :intentfilteraction intent-filter-action
                     :intentfiltercategory intent-filter-category})))))
      ;; any more?
      )
    (let [conn (connect options)
          transaction (ntx/begin-tx conn)]
      (ntx/commit conn transaction @statements))))

(let [common (fn [apk labels options op]
               (when-not (empty? labels)
                 (let [statements (atom [])]
                   (let [manifest (:manifest apk)
                         apk-sha256 (:sha256 apk)]
                     (swap! statements conj
                            (ntx/statement
                             (str/join " "
                                       ["MATCH (n:Apk)"
                                        "WHERE n.sha256={apksha256}"
                                        ;; set the labels
                                        (str/join " "
                                                  (map #(format "%1$s n:%2$s" op %)
                                                       labels))])
                             {:apksha256 apk-sha256})))
                   (let [conn (connect options)
                         transaction (ntx/begin-tx conn)]
                     (try
                       (ntx/commit conn transaction @statements)
                       (catch Exception e
                         (print-stack-trace e)))))))]
  
  (defn tag-apk
  "tag an existing Apk node with the labels"
  [apk labels
   {:keys [] :as options}]
  (common apk labels options "SET"))
  (defn untag-apk
  "untag an existing Apk node with the labels"
  [apk labels
   {:keys [] :as options}]
  (common apk labels options "REMOVE")))

(defn create-index
  "create index"
  [{:keys []
    :as options}]
  (let [statements (map ntx/statement
                        (map (fn [[label prop]]
                               (str "CREATE INDEX ON :"
                                    label "(" prop ")"))
                             {"SigningKey" "sha256"
                              "Apk" "sha256"
                              "Dex" "sha256"
                              "Permission" "name"
                              "Package" "name"
                              "Class" "name"
                              "Method" "name"
                              "Callback" "name"
                              "Activity" "name"
                              "Service" "name"
                              "Receiver" "name"
                              "IntentFilterAction" "name"
                              "IntentFilterCategory" "name"
                              "AndroidAPI" "name"}))]
    (let [conn (connect options)
          transaction (ntx/begin-tx conn)]
      (ntx/commit conn transaction statements))))

(defn mark-android-api
  "label =~'^(?:com.)?android' nodes as AndroidAPI; should be infrequently used"
  [{:keys []
    :as options}]
  (let [conn (connect options)
        transaction (ntx/begin-tx conn)]
    (ntx/with-transaction
      conn
      transaction
      true
      (ntx/execute conn transaction
                   [(ntx/statement
                     (str/join " "
                               ["MATCH (n)"
                                "WHERE n.name=~{regex}"
                                "SET n:AndroidAPI"])
                     {:regex "L?(?:android\\.|com\\.android\\.|dalvik\\.).*"})]))))

(defn connect
  "connect to local neo4j server at PORT"
  [{:keys [neo4j-port neo4j-protocol verbose]
    :as options
    :or {neo4j-port (:neo4j-port @defaults)
         neo4j-protocol (:neo4j-protocol @defaults)}}]
  (let [port (if neo4j-port neo4j-port (:neo4j-port @defaults))
        protocol (if neo4j-protocol neo4j-protocol (:neo4j-protocol @defaults))]
    (try
      (nr/connect (format "%1$s://localhost:%2$d/db/data/" protocol port))
      (catch Exception e
        (print-stack-trace-if-verbose e verbose)))))


(defn android-api?
  "test whether NAME is part of Android API"
  [name]
  (let [name (str name)]
    (re-find #"^L?(?:android\.|com\.android\.|dalvik\.)" name)))

