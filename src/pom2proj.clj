(ns pom2proj
  (:require [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.data.zip.xml :as zx])
  (:use [clojure.pprint :only [pprint]]))

(defn- text-attrs
  [loc ks]
  (map (fn [k]
           (zx/xml1-> loc k zx/text))
       ks))

(defn- attr-map
  [loc ks]
  (if (nil? loc)
    nil
    (reduce (fn [m k]
            (assoc m k (zx/xml1-> loc k zx/text)))
          {} ks)))

(defn- map-by-attr
  [locs k-key v-key]
  (reduce (fn [m loc]
              (let [[k v] (text-attrs loc [k-key v-key])]
                (assoc m k v)))
          {} locs))

(defn- artifact-sym
  [{:keys [groupId artifactId]}]
  (symbol (if (= groupId artifactId)
            artifactId
            (str groupId "/" artifactId))))

(defn- exclusions
  [dep-loc]
  (map #(attr-map % [:groupId :artifactId])
       (zx/xml-> dep-loc :exclusions :exclusion)))

(defn- dependency
  [dep-loc]
  (let [dep (attr-map dep-loc [:groupId :artifactId :version])
        exclusions (exclusions dep-loc)]
    (if (empty? exclusions)
      dep
      (assoc dep :exclusions exclusions))))

(defn- dependencies
  [pom-zip]
  (zx/xml-> pom-zip :dependencies :dependency dependency))

(defn- parent
  [pom-zip]
  (attr-map (zx/xml1-> pom-zip :parent)
            [:groupId :artifactId :version :relativePath]))

(defn- project
  [pom-zip]
  (attr-map pom-zip
            [:groupId :artifactId :version :description :url]))

(defn- repositories
  [pom-zip]
  (map-by-attr (zx/xml-> pom-zip :repositories :repository)
               :id :url))

(defn- deploy-repositories
  [pom-zip]
  (let [dist (zx/xml1-> pom-zip :distributionManagement)]
    (if (not (nil? dist))
      {:releases (zx/xml1-> dist :repository :url zx/text)
     :snapshots (zx/xml1-> dist :snapshotRepository :url zx/text)}))) 

(defn- build
  [pom-zip]
  (let [bld (zx/xml1-> pom-zip :build)]
    {:source-paths (let [sources (zx/xml1-> bld :sourceDirectory zx/text)] (if (nil? sources) ["src/main/clojure" "src/main/java"] [sources]))
     :test-paths (let [tests (zx/xml1-> bld :testSourceDirectory zx/text)] (if (nil? tests) ["src/test/clojure" "src/test/java"] [tests]))
     :resource-paths (let [resources (zx/xml-> bld :resources :resource :directory zx/text)] (if (or (nil? resources) (empty? resources)) ["src/main/resources"] [resources]))}))

(defn- lein-project-info
  [xz]
  (assoc (merge (project xz) (build xz))
    :dependencies (dependencies xz)
    :repositories (repositories xz)
    :deploy-repositories (deploy-repositories xz)
    :parent (parent xz)))

(defn- format-dependencies
  [deps]
  (into [] (map (fn [dep]
                  (let [d [(artifact-sym dep) (:version dep)]
                        ex (:exclusions dep)]
                    (if ex
                      (conj d :exclusions
                            (into [] (map artifact-sym ex)))
                      d)))
                deps)))

(defn- format-parent
  [parent]
  (if (nil? parent)
    nil
    (let [rel (:relativePath parent)
        p [(artifact-sym parent)]]
    (if rel
      (conj p :relative-path rel)
      p))))

(defn- format-lein-project
  [{:keys [dependencies repositories parent] :as project}]
  (let [info (dissoc project :groupId :artifactId :version)
        info (assoc info
               :dependencies (format-dependencies dependencies)
               :parent (format-parent parent))
        info (into {} (filter (fn [[k v]] v) info))
        pf `(~'defproject ~(artifact-sym project) ~(:version project)
            ~@(mapcat (fn [[k v]] [k v]) info))]
    pf))

(defn process-pom
  ([from to]
     (let [xz (zip/xml-zip (xml/parse (io/file (str from "pom.xml"))))
           project-info (lein-project-info xz)
           f (format-lein-project project-info)]
       (spit (str to "project.clj") (with-out-str (pprint f)))
       (pprint f)))
  ([from]
     (process-pom from from)))
