(defproject ring-transit "0.1.1"
  :description "Ring middleware for handling transit format"
  :url "https://github.com/jalehman/ring-transit"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :scm {:name "git"
        :url "https://github.com/jalehman/ring-transit"}

  :signing {:gpg-key "3916C690"}

  :deploy-repositories [["clojars" {:creds :gpg}]]

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.cognitect/transit-clj "0.8.229"]
                 [prismatic/plumbing "0.3.3"]
                 [ring/ring-core "1.3.0"]])
