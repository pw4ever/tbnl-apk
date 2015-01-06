(ns tbnl-apk.apk.dex.soot.emulator
  ;; internal libs
  (:require [tbnl-apk.util
             :refer [print-stack-trace-if-verbose]])   
  (:use tbnl-apk.apk.dex.soot.helper)
  ;; common libs
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.stacktrace :refer [print-stack-trace]])
  ;; imports
  (:import (soot Unit
                 SootField
                 SootClass
                 SootMethod
                 SootMethodRef

                 Local

                 RefLikeType
                 ArrayType
                 RefType)
           
           (soot.jimple Stmt
                        StmtSwitch
                        JimpleValueSwitch)))

;;; declaration

(declare  get-method-implicit-cf get-method-detailed-invokes
         filter-implicit-cf-invoke-methods implicit-cf-methodref? implicit-cf-class?

         ;; plumbing
         emulate-a-basic-block create-emulator
         get-transitive-implicit-cf-super-class-and-interface)

;;; implementation

(def ^:dynamic *object-embedding-hack*
  "use to work around Cannot Embed Object in Code exception in eval"
  nil)

;;; porcelain

(defn get-method-implicit-cf
  "get method's implicit control flows (e.g., reflection)"
  [implicit-cf ^SootMethod method
   options]
  (when (.. method hasActiveBody)
    (let [all-implicit-invokes (->> method
                                    (#(get-method-detailed-invokes % options))
                                    (filter (fn [i]
                                              (->> i first
                                                   (implicit-cf-methodref? implicit-cf)))))]
      all-implicit-invokes)))



(defn get-method-detailed-invokes
  "get method's detailed invokes"
  [^SootMethod method
   {:keys [verbose]
    :as options}]
  (when (.. method hasActiveBody)
    (let [body (.. method getActiveBody)
          
          stmt-info
          (let [stmts (->> (.. body getUnits snapshotIterator) iterator-seq vec doall)
                stmt-2-index (->> stmts
                                  (map-indexed #(vector %2 %1))
                                  (into {}))]
            {:stmts stmts
             :stmt-2-index stmt-2-index})
          
          all-invokes (atom #{})
          all-visited-stmts (atom #{})]
      (process-worklist
       ;; the initial worklist
       #{{:emulator (create-emulator)
          :start-stmt (first (:stmts stmt-info))}}

       ;; the process
       (fn [worklist]
         (->> worklist
              (mapcat (fn [{:keys [emulator start-stmt]}]
                        (let [{:keys [emulator visited-stmts next-start-stmts]}
                              (emulate-a-basic-block emulator stmt-info start-stmt
                                                     :host-method method
                                                     :verbose verbose)]
                          (swap! all-visited-stmts set/union (set visited-stmts))
                          (swap! all-invokes into (:invoke emulator))
                          (for [start-stmt (set/difference (set next-start-stmts)
                                                           @all-visited-stmts)]
                            ;; use the emulator snapshot at this branch --- control flow sensitive!
                            {:emulator (assoc-in emulator [:invoke] #{})
                             :start-stmt start-stmt})))))))
      @all-invokes)))

(defn filter-implicit-cf-invoke-methods
  "filter methods that contain implicit control flow invokes"
  [implicit-cf methods]
  (->> methods
       (filter
        (fn [^SootMethod method]
          (->> [method]
               mapcat-invoke-methodrefs
               (filter #(implicit-cf-methodref? implicit-cf %))
               not-empty)))))

(defn implicit-cf-class?
  "test whether class possibly contains implicit cf"
  [implicit-cf ^SootClass class]
  (not-empty (get-transitive-implicit-cf-super-class-and-interface implicit-cf
                                                                   class)))

(defn get-transitive-implicit-cf-super-class-and-interface
  "get class's implicit cf super classes/interfaces"
  [implicit-cf ^SootClass class]
  (set/intersection (set (keys implicit-cf))
                    (->> class
                         get-transitive-super-class-and-interface
                         (map #(.. ^SootClass % getName))
                         set)))

(defn get-implicit-cf-root-class-names
  "get methodref's root implicit-cf classes' names"
  [implicit-cf ^SootMethodRef methodref]
  (let [methodref-class (.. methodref declaringClass)
        methodref-name (.. methodref name)]
    (->> (get-transitive-implicit-cf-super-class-and-interface implicit-cf
                                                               methodref-class)
         (filter #(or (= (get implicit-cf %) :all)
                      (contains? (get implicit-cf %)
                                 methodref-name)))
         not-empty)))

(def implicit-cf-methodref?
  "test whether methodref is possibly an implicit cf"
  get-implicit-cf-root-class-names)

;;; plumbing

(defn emulate-a-basic-block
  "emulator emulate operation"
  [emulator
   {:keys [stmts stmt-2-index]}
   start-stmt
   & {:keys [safe-invokes ^SootMethod host-method verbose]
      ;; safe classes are the ones that can be simulated with Clojure
      :or {safe-invokes {"java.lang.String" :all
                         "java.lang.StringBuilder" :all
                         "java.lang.StringBuffer" :all
                         "java.lang.Math" :all
                         "java.lang.StrictMath" :all
                         "java.lang.Integer" :all
                         "java.lang.Long" :all
                         "java.lang.Double" :all
                         "java.lang.Float" :all
                         "java.lang.Byte" :all
                         "java.lang.Character" :all
                         "java.lang.Short" :all
                         "java.lang.Boolean" :all
                         "java.lang.Void" :all
                         "java.lang.System" #{"nanoTime"
                                              "currentTimeMillis"}}}}]
  
  (let [;; the emulator state
        emulator (atom emulator)

        ;; parsing value expression
        value-switch
        (fn
          ^{:doc "return an *atom* (for scalar) or a *vector* (for arrays) if it is a value; otherwise, return a *list* beginning with a keyword identifying its type"}
          value-switch
          [value]
          
          (let [result (atom nil)
                ;; resolve a soot.Value reference
                resolve-value (fn [x]
                                (try
                                  (cond
                                    (instance? soot.Local x)
                                    (->> @emulator :state (#(get % x)))

                                    (instance? soot.jimple.MethodHandle x)
                                    (.. x methodRef)

                                    (instance? soot.jimple.NullConstant x)
                                    nil

                                    (instance? soot.jimple.Constant x)
                                    (.. x value)

                                    :default x)
                                  (catch Exception e
                                    (print-stack-trace-if-verbose e verbose 2)
                                    x)))
                ;; binary operation
                bin-op-expr (fn [expr op-func op-kw]
                              (let [op1 (.. expr getOp1)
                                    op2 (.. expr getOp2)
                                    default-ret (list op-kw [op1 op2])]
                                (try
                                  (let [op1 (resolve-value op1)
                                        op2 (resolve-value op2)]
                                    (op-func op1 op2))
                                  (catch Exception e
                                    (print-stack-trace-if-verbose e verbose 2)
                                    default-ret))))
                ;; unary operation
                un-op-expr (fn [expr op-func op-kw]
                             (let [op (.. expr getOp)
                                   default-ret (list op-kw [op])]
                               (try
                                 (let [op (resolve-value op)]
                                   (op-func op))
                                 (catch Exception e
                                   (print-stack-trace-if-verbose e verbose 2)
                                   default-ret))))
                ;; invoke operation
                invoke-expr (fn [^SootMethodRef methodref base args invoke-kw]
                              ;; first try evaluating safe classes
                              (let [class-name (.. methodref declaringClass getName)
                                    method-name (.. methodref name)
                                    base (resolve-value base)
                                    args (->> args
                                              (map resolve-value)
                                              vec) 
                                    default-ret (list :invoke
                                                      (list invoke-kw
                                                            (list methodref
                                                                  base
                                                                  args)))]
                                (try
                                  (if (let [t (get safe-invokes class-name)]
                                        (or (= t :all)
                                            (contains? t method-name)))
                                    (cond
                                      ;; special invokes: <init>
                                      (= invoke-kw :special-invoke)
                                      (eval `(new ~(symbol class-name) ~@args))

                                      ;; interface and virtual invokes
                                      (and base
                                           (not (list? base)))
                                      (do
                                        (binding [*object-embedding-hack* (atom base)]
                                          (eval `(. @*object-embedding-hack*
                                                    (~(symbol method-name) ~@args)))))
                                      
                                      ;; static invokes
                                      (nil? base)
                                      (eval `(. ~(symbol class-name)
                                                (~(symbol method-name) ~@args)))

                                      :default default-ret)
                                    default-ret)
                                  (catch Exception e
                                    (print-stack-trace-if-verbose e verbose 2)
                                    default-ret))))]
            (try
              (.. value
                  (apply
                   (proxy [JimpleValueSwitch] []
                     ;; case local
                     (caseLocal [^Local local]
                       (reset! result
                               (->> @emulator :state (#(get % local)))))
                     ;; ConstantSwitch
                     (caseClassConstant [const]
                       (reset! result
                               (.. const getType getSootClass)))
                     (caseDoubleConstant [const]
                       (reset! result
                               (.. const value)))
                     (caseFloatConstant [const]
                       (reset! result
                               (.. const value)))
                     (caseIntConstant [const]
                       (reset! result
                               (.. const value)))
                     (caseLongConstant [const]
                       (reset! result
                               (.. const value)))
                     (caseMethodHandle [const]
                       (reset! result
                               (.. const getMethodRef)))
                     (caseNullConstant [const]
                       (reset! result
                               nil))
                     (caseStringConstant [const]
                       (reset! result
                               (.. const value)))
                     ;; ExprSwitch
                     (caseAddExpr [expr]
                       (reset! result
                               (bin-op-expr expr + :add)))
                     (caseAndExpr [expr]
                       (reset! result
                               (bin-op-expr expr bit-and :and)))
                     (caseCastExpr [expr]
                       ;; no effect on result
                       )
                     (caseCmpExpr [expr]
                       (reset! result
                               (bin-op-expr expr compare :cmp)))
                     (caseCmpgExpr [expr]
                       ;; JVM-specific artifacts; N/A on Dalvik
                       (reset! result
                               (bin-op-expr expr compare :cmpg)))
                     (caseCmplExpr [expr]
                       ;; JVM-specific artifacts; N/A on Dalvik
                       (reset! result
                               (bin-op-expr expr compare :compl)))
                     (caseDivExpr [expr]
                       (reset! result
                               (bin-op-expr expr / :div)))
                     (caseDynamicInvokeExpr [expr]
                       ;; JVM8 specific; N/A on Dalvik
                       (reset! result
                               (invoke-expr (.. expr getBootstrapMethodRef)
                                            nil
                                            (.. expr getBootstrapArgs)
                                            :dynamic-invoke)))
                     (caseEqExpr [expr]
                       (reset! result
                               ;; only non-sexp can be meaningfully compared
                               (bin-op-expr expr
                                            (fn [op1 op2]
                                              (if (and (not (list? op1))
                                                       (not (list? op2)))
                                                (== op1 op2)
                                                (list :eq [op1 op2]))) 
                                            :eq)))
                     (caseGeExpr [expr]
                       (reset! result
                               (bin-op-expr expr >= :ge)))
                     (caseGtExpr [expr]
                       (reset! result
                               (bin-op-expr expr > :gt)))
                     (caseInstanceOfExpr [expr]
                       (reset! result
                               (let [op (.. expr getOp)
                                     check-type (.. expr getCheckType)
                                     default-ret (list :instance-of check-type op)]
                                 (try
                                   (let [op (->> op resolve-value)
                                         check-type-name (.. check-type getClassName)
                                         check-type-class (.. check-type getSootClass)]
                                     (cond
                                       (#{:invoke} (first op))
                                       (let [type-class (.. (->> op second second first)
                                                            declaringClass)]
                                         (if (transitive-ancestor? check-type-class
                                                                   type-class)
                                           ;; only positive answer is certain
                                           1
                                           default-ret))

                                       (#{:param-ref :this-ref} (first op))
                                       (let [type-class (.. (->> op second first)
                                                            getSootClass)]
                                         (if (transitive-ancestor? check-type-class
                                                                   type-class)
                                           ;; only positive answer is certain
                                           1
                                           default-ret))

                                       :default default-ret))
                                   (catch Exception e
                                     (print-stack-trace-if-verbose e verbose 2)
                                     default-ret)))))
                     (caseInterfaceInvokeExpr [expr]
                       (reset! result
                               (invoke-expr (.. expr getMethodRef)
                                            (.. expr getBase)
                                            (.. expr getArgs)
                                            :interface-invoke)))
                     (caseLeExpr [expr]
                       (reset! result
                               (bin-op-expr expr <= :le)))
                     (caseLengthExpr [expr]
                       (reset! result
                               (un-op-expr expr count :length)))
                     (caseLtExpr [expr]
                       (reset! result
                               (bin-op-expr expr < :lt)))
                     (caseMulExpr [expr]
                       (reset! result
                               (bin-op-expr expr * :mul)))
                     (caseNeExpr [expr]
                       (reset! result
                               (bin-op-expr expr
                                            ;; only non-sexp can be meaningfully compared
                                            (fn [op1 op2]
                                              (if (and (not (list? op1))
                                                       (not (list? op2)))
                                                (not= op1 op2)
                                                (list :ne [op1 op2])))
                                            :ne)))
                     (caseNegExpr [expr]
                       (reset! result
                               (un-op-expr expr - :neg)))
                     (caseNewArrayExpr [expr]
                       (reset! result
                               (let [base-type (.. expr getBaseType)
                                     size (.. expr getSize)
                                     default-ret (list :new-array [base-type size])]
                                 (try
                                   (let [size (cond
                                                (instance? soot.Immediate size)
                                                (.. size value)

                                                (instance? soot.Local size)
                                                (->> @emulator :state (#(get % size))))]
                                     ;; use Clojure vector to simulate Java array
                                     (->> (repeat size base-type)
                                          vec))
                                   (catch Exception e
                                     (print-stack-trace-if-verbose e verbose 2)
                                     default-ret)))))
                     (caseNewExpr [expr]
                       ;; will be evaluated in caseSpecialInvokeExpr where the arguments are ready
                       )
                     (caseNewMultiArrayExpr [expr]
                       (reset! result
                               (let [base-type (.. expr getBaseType)
                                     sizes (->> (.. expr getSizes) vec)
                                     default-ret (list :new-multi-array [base-type sizes])]
                                 (try
                                   (let [total-size (->> sizes
                                                         (map (fn [size]
                                                                (cond
                                                                  (instance? soot.Immediate size)
                                                                  (.. size value)

                                                                  (instance? soot.Local size)
                                                                  (->> @emulator :state (#(get % size))))))
                                                         (reduce *))]
                                     (->> (repeat total-size base-type)
                                          vec))
                                   (catch Exception e
                                     (print-stack-trace-if-verbose e verbose 2)
                                     default-ret)))))
                     (caseOrExpr [expr]
                       (reset! result
                               (bin-op-expr expr bit-or :or)))
                     (caseRemExpr [expr]
                       (reset! result
                               (bin-op-expr expr rem :rem)))
                     (caseShlExpr [expr]
                       (reset! result
                               (bin-op-expr expr not= :ne)))
                     (caseShrExpr [expr]
                       (reset! result
                               (bin-op-expr expr bit-shift-right :shr)))
                     (caseSpecialInvokeExpr [expr]
                       (reset! result
                               (invoke-expr (.. expr getMethodRef)
                                            (.. expr getBase)
                                            (.. expr getArgs)
                                            :special-invoke)))
                     (caseStaticInvokeExpr [expr]
                       (reset! result
                               (invoke-expr (.. expr getMethodRef)
                                            nil
                                            (.. expr getArgs)
                                            :static-invoke)))
                     (caseSubExpr [expr]
                       (reset! result
                               (bin-op-expr expr - :sub)))
                     (caseUshrExpr [expr]
                       (reset! result
                               (bin-op-expr expr unsigned-bit-shift-right :ushr)))
                     (caseVirtualInvokeExpr [expr]
                       (reset! result
                               (invoke-expr (.. expr getMethodRef)
                                            (.. expr getBase)
                                            (.. expr getArgs)
                                            :virtual-invoke)))
                     (caseXorExpr [expr]
                       (reset! result
                               (bin-op-expr expr bit-xor :xor)))
                     ;; RefSwitch
                     (caseArrayRef [ref]
                       (reset! result
                               (let [base (->> (.. ref getBase) resolve-value)
                                     index (->> (.. ref getIndex) resolve-value)
                                     default-ret (list :array-ref [base index])]
                                 (try
                                   (get base index)
                                   (catch Exception e
                                     (print-stack-trace-if-verbose e verbose 2)
                                     default-ret)))))
                     (caseCaughtExceptionRef [ref]
                       ;; irrelevant
                       )
                     (caseInstanceFieldRef [ref]
                       (reset! result
                               (.. ref getFieldRef)))
                     (caseParameterRef [ref]
                       (reset! result
                               (list :param-ref [(.. ref getType) (.. ref getIndex)])))
                     (caseStaticFieldRef [ref]
                       (reset! result
                               (.. ref getFieldRef)))
                     (caseThisRef [ref]
                       (reset! result
                               (list :this-ref
                                     [(if host-method
                                        (.. host-method getDeclaringClass getType)
                                        (.. ref getType))])))  
                     ;; default case
                     (defaultCase [expr]))))
              (catch Exception e
                (print-stack-trace-if-verbose e verbose)))
            @result))
        
        [basic-block residue] (split-with #(let [stmt ^Stmt %]
                                             (and (.. stmt fallsThrough)
                                                  (not (.. stmt branches))))
                                          ;; all stmts from start-stmt to the end
                                          (subvec stmts
                                                  (get stmt-2-index
                                                       start-stmt)))]

    (let [update-invoke (fn [val]
                          (when (and (list? val)
                                     (= (first val) :invoke))
                            (swap! emulator update-in
                                   [:invoke] conj
                                   (->> val second second))))]
      (doseq [^Stmt stmt basic-block]
        (.. stmt
            (apply (proxy [StmtSwitch] []
                     (caseAssignStmt [stmt]
                       (let [var (.. stmt getLeftOp)
                             val (->> (.. stmt getRightOp)
                                      value-switch)]
                         (update-invoke val)
                         (swap! emulator assoc-in
                                [:state var]
                                val)))
                     (caseBreakpointStmt [stmt])
                     (caseEnterMonitorStmt [stmt])
                     (caseExitMonitorStmt [stmt])
                     (caseGotoStmt [stmt])
                     (caseIdentityStmt [stmt]
                       (try
                         (let [var (.. stmt getLeftOp)
                               val (->> (.. stmt getRightOp)
                                        value-switch)]
                           (update-invoke val)
                           (swap! emulator assoc-in
                                  [:state var]
                                  val))
                         (catch Exception e
                           (print-stack-trace-if-verbose e verbose 2))))
                     (caseIfStmt [stmt])
                     (caseInvokeStmt [stmt]
                       (try
                         (let [invoke-expr (.. stmt getInvokeExpr)
                               val (->> invoke-expr value-switch)]
                           (update-invoke val)
                           ;; static-invoke does not have base, so might throw Exception
                           (let [base (.. invoke-expr getBase)]                           
                             (swap! emulator assoc-in
                                    [:state base]
                                    val)))
                         (catch Exception e
                           (print-stack-trace-if-verbose e verbose 2))))
                     (caseLookupSwitchStmt [stmt])
                     (caseNopStmt [stmt])
                     (caseRetStmt [stmt])
                     (caseReturnStmt [stmt])
                     (caseReturnVoidStmt [stmt])
                     (caseTableSwitchStmt [stmt])
                     (caseThrowStmt [stmt])
                     (defaultCase [stmt]))))))
    
    (let [ret (atom {:emulator @emulator
                     :visited-stmts basic-block
                     :next-start-stmts #{}})
          ;; the first stmt of residue, if existed, is a brancher      
          stmt (first residue)]
      ;; find next-start-stmts
      (when stmt
        (swap! ret update-in [:visited-stmts]
               conj stmt)
        (.. stmt
            (apply (proxy [StmtSwitch] []
                     (caseAssignStmt [stmt])
                     (caseBreakpointStmt [stmt])
                     (caseEnterMonitorStmt [stmt])
                     (caseExitMonitorStmt [stmt])
                     (caseGotoStmt [^soot.jimple.internal.JGotoStmt stmt]
                       (swap! ret update-in [:next-start-stmts]
                              conj (.. stmt getTarget)))                     
                     (caseIdentityStmt [stmt])
                     (caseIfStmt [^soot.jimple.internal.JIfStmt stmt]
                       (let [condition (.. stmt getCondition)
                             val (->> condition value-switch)                             
                             target-stmt (.. stmt getTarget)
                             next-stmt (second residue)]
                         (if-not (coll? val)
                           (if val
                             ;; if val is true, take target-stmt
                             (when target-stmt
                               (swap! ret update-in [:next-start-stmts]
                                      conj target-stmt))
                             ;; if val is false, take next-stmt
                             (when next-stmt
                               (swap! ret update-in [:next-start-stmts]
                                      conj next-stmt)))
                           ;; otherwise, take both stmts
                           (doseq [stmt [next-stmt target-stmt]
                                   :when stmt]
                             (swap! ret update-in [:next-start-stmts]
                                    conj stmt)))))                     
                     (caseInvokeStmt [stmt])
                     (caseLookupSwitchStmt [stmt])
                     (caseNopStmt [stmt])
                     (caseRetStmt [stmt])
                     (caseReturnStmt [stmt])
                     (caseReturnVoidStmt [stmt])
                     (caseTableSwitchStmt [stmt])
                     (caseThrowStmt [stmt])
                     (defaultCase [stmt])))))
      @ret)))

(defn create-emulator
  "create a fresh emulator"
  []
  {:state {}
   :invoke #{}})

