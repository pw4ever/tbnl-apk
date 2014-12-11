(ns tbnl-apk.apk.aapt.parse
  ;; internal libs
  ;; common libs
  (require [clojure.string :as str]
           [clojure.set :as set]
           [clojure.walk :as walk]
           [clojure.zip :as zip]
           [clojure.java.io :as io])
  ;; special libs
  (require [clojure.java.shell :as shell :refer [sh]]))

(def manifest "AndroidManifest.xml")

(declare get-badging get-manifest)
(declare parse-aapt-xmltree
         get-nodes-from-parsed-xmltree)
(declare aapt-dump-xmltree
         aapt-dump-badging
         aapt-dump-manifest)

(defn get-badging [apk]
  "get badging in Clojure data structure"
  (let [result (atom {})
        get-string-in-single-quotes #(if-let [[_ meat] (re-find #"^'([^']+)'$" %)]
                                       meat
                                       %)]
    ;; first pass
    (doseq [line (str/split-lines (aapt-dump-badging apk))]
      ;; only consider lines that have values
      (when-let [[_ label content] (re-find #"^([^:]+):([^:]+)$" line)]
        (let [label (keyword label)]
          (swap! result update-in [label]
                 conj content))))
    ;; second pass
    (doseq [k (keys @result)]
      (swap! result update-in [k]
             (fn [content]
               (when-let [first-item (first content)]
                 (cond
                  ;; strings
                  (re-matches #"'[^']+'" first-item)
                  (into #{}
                        (map get-string-in-single-quotes
                             content))
                  ;; map
                  (re-matches #"(?:\s[^\s'][^\s=]+='[^']+')+" first-item)
                  (into {}
                        (for [[_ k v] (re-seq #"\s([^\s=]+)='([^']+)'" first-item)]
                          [(keyword k)
                           v]))
                  ;; set
                  (re-matches #"(?:\s'[^']+')+" first-item)
                  (into #{}
                        (for [[_ v] (re-seq #"\s'([^']+)'" first-item)]
                          v))
                  ;; sequence
                  (re-matches #"'[^']+',.+" first-item)
                  (into #{}
                        (map #(into []
                                    (map (fn [[_ meat]]
                                           meat)
                                         (re-seq #"'([^']+)',?" %)))
                             content)))))))
    @result))

(defn get-manifest [apk]
  "get manifest in Clojure data structure

reference: https://developer.android.com/guide/topics/manifest/manifest-intro.html"
  (let [parsed-manifest (parse-aapt-xmltree (aapt-dump-manifest apk))
        result (atom {})
        get-node-android-name (fn [node package]
                                (-> node
                                  (get-in [:attrs :android:name])
                                  str
                                  (#(if (.startsWith ^String % ".")
                                      (str package %)
                                      %))
                                  keyword))]
    ;; <manifest> attrs
    (let [node (first (get-nodes-from-parsed-xmltree parsed-manifest
                                                     [:manifest]))]
      (doseq [attr [:android:versionCode
                    :android:versionName
                    :package]]
        (swap! result assoc-in [attr]
               (get-in node [:attrs attr]))))
    (let [package (get-in @result [:package])]
      ;; <manifest> level
      (doseq [node [:uses-permission
                    :permission]]
        (swap! result assoc-in [node]
               (set (map #(get-node-android-name % package)
                         (get-nodes-from-parsed-xmltree parsed-manifest
                                                        [:manifest node])))))
      ;; <application> level
      (doseq [node [:activity
                    :activity-alias
                    :service
                    :receiver]]
        (swap! result assoc-in [node]
               (set (map (fn [node]
                           {(get-node-android-name node package)
                            (into {}
                                  (map (fn [intent-filter-tag]
                                         [(keyword (str "intent-filter-"
                                                        (name intent-filter-tag)))
                                          (set (map #(get-node-android-name % package)
                                                    (get-nodes-from-parsed-xmltree (:content node)
                                                                                   [:intent-filter
                                                                                    intent-filter-tag])))])
                                       [:action :category]))})
                         (get-nodes-from-parsed-xmltree parsed-manifest
                                                        [:manifest :application node]))))))
    @result))

(defn parse-aapt-xmltree [xmltree-dump]
  "parse aapt xmltree dump into Clojure data structure"
  (let [tmp (map #(let [[_ spaces type raw]
                        (re-find #"^(\s+)(\S):\s(.+)$"
                                 %)]
                    {:indent (count spaces)
                     :type type
                     :raw raw})
                 (str/split-lines xmltree-dump))
        namespace (:raw (first tmp))
        lines (vec (rest tmp))
        ;; first pass build: from lines to a tree        
        build (fn build [lines]
                (when-let [lines (vec lines)]
                  (when (not (empty? lines))
                    (let [start-indent (:indent (first lines))
                          segment-indexes (vec (concat (keep-indexed #(when (<= (:indent %2)
                                                                                start-indent)
                                                                        %1)
                                                                     lines)
                                                       [(count lines)]))
                          segments (map #(subvec lines
                                                 (get segment-indexes %)
                                                 (get segment-indexes (inc %)))
                                        (range (dec (count segment-indexes))))]
                      (vec (map (fn [lines]
                                  (let [line (first lines)
                                        lines (rest lines)
                                        type (:type line)
                                        raw (:raw line)]
                                    (case type
                                      ;; Element
                                      "E"
                                      (let [[_ name line] (re-find #"^(\S+)\s+\(line=(\d+)\)$"
                                                                   raw)]
                                        {:type :element
                                         :name name
                                         :line line
                                         :children (build lines)})
                                      ;; Attribute
                                      "A"
                                      (let [[_
                                             encoded-name bare-name
                                             quoted-value encoded-value bare-value] (re-find
                                                                                     #"(?x)
^(?:
  ([^=(]+)\([^)]+\)| # encoded name
  ([^=(]+) # bare name
)
=
(?:
  \"([^\"]+)\"| # quoted value
  \([^)]+\)(\S+)|  # encoded value
  ([^\"(]\S*) # bare value
)
"
                                                                                     raw)]
                                        {:type :attribute
                                         :name (or bare-name encoded-name)
                                         :value (or quoted-value encoded-value bare-value)})))) segments))))))
        pass (build lines)]
    (let [;; second pass: merge attributes into element
          build (fn build [node]
                  (when (= (:type node) :element)
                    (let [[attrs elems] (split-with #(= (:type %) :attribute)
                                                    (:children node))]
                      {:tag (keyword (:name node))
                       :attrs (into {} (map #(do [(keyword (:name %))
                                                  (:value %)])
                                            attrs))
                       :content (set (map build elems))})))
          pass (set (map build pass))]
      pass)))

(defn get-nodes-from-parsed-xmltree [parsed-xmltree [tag & more-tags]]
  "get nodes from parsed xmltree"
  (->> parsed-xmltree
       (filter #(= (:tag %) tag))
       ((fn [nodes]
          (if more-tags
            (mapcat #(get-nodes-from-parsed-xmltree (:content %)
                                                    more-tags)
                    nodes)
            nodes)))
       set))

(defn aapt-dump-xmltree [apk asset]
  "aapt dump xmltree asset"
  (:out (sh "aapt" "dump" "xmltree"
            apk asset)))

(defn aapt-dump-badging [apk]
  "aapt dump badging apk"
  (:out (sh "aapt" "dump" "badging"
            apk)))

(defn aapt-dump-manifest [apk]
  "aapt dump xmltree <manifest>"
  (aapt-dump-xmltree apk manifest))
