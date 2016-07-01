(defproject ring-transit "0.1.5"
  :description "Ring middleware for handling transit format"
  :url "https://github.com/jalehman/ring-transit"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :scm {:name "git"
        :url "https://github.com/jalehman/ring-transit"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [com.cognitect/transit-clj "0.8.285"]
                 [prismatic/plumbing "0.5.0"]
                 [ring/ring-core "1.4.0"]])
