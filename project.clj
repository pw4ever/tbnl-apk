(defproject tbnl-apk "0.1.0-SNAPSHOT"
  :description ""
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.cli "0.3.1"]
                 [asmdex/asmdex "1.0"]
                 [pandect "0.4.1"]
                 [commons-io/commons-io "2.4"]
                 [me.raynes/fs "1.4.6"]]
  :plugins [[lein-localrepo "0.5.3"]]
  :main ^:skip-aot tbnl-apk.core
  :target-path "target/%s"
  :uberjar-name "tbnl-apk.jar"
  :profiles {:uberjar {:aot :all}})
