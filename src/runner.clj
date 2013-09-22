(ns runner
  (:use pom2proj)
  (:gen-class))

(defn -main [& args]
  (process-pom (first args) (second args)))
