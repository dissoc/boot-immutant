(ns boot.immutant
  (:require [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [boot.util :as util]
            [boot.task.built-in :as built-in]
            [boot.tmpdir :as tmpd]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [boot.from.backtick :as bt])
  (:import java.util.Properties))

(defn ^:private load-properties [resource-name]
  (doto (Properties.)
    (.load (-> resource-name io/resource io/reader))))

(def ^:private versions
  (delay
    (let [version-props (load-properties "versions.properties")
          pom-props (load-properties "META-INF/maven/boot-immutant/boot-immutant/pom.properties")]
      (into
        {:boot-immutant (.getProperty pom-props "version")}
        (for [k (.stringPropertyNames version-props)]
          [(keyword k) (.getProperty version-props k)])))))


(def ^:private pod
  (delay
    (pod/make-pod
      (assoc pod/env
        :dependencies [['org.immutant/deploy-tools (:deploy-tools @versions)]
                       ['org.immutant/fntest       (:fntest @versions)]
                       ['boot-immutant             (:boot-immutant @versions)]
                       ['boot/aether               boot/*boot-version*]]))))

(defn ^:private gen-classpath
  [{:keys [source-paths resource-paths] :as env}]
  (into (mapv #(-> % io/file .getAbsolutePath)
          (concat source-paths resource-paths))
    (pod/with-call-worker
      (boot.aether/resolve-dependency-jars ~env))))

(defn ^:private gen-uberjar [env]
  ;; TODO: it would be nice if the jar task took an absolute path
  (let [fname "project-uber.jar"]
    (boot/boot (built-in/uber) (built-in/jar :file fname))
    (.getAbsolutePath
      (doto (io/file (:target-path env) fname)
        (.deleteOnExit)))))

(defn ensure-dir [path]
  (.mkdirs (io/file path)))

(defn ^:private war-machine [{:keys [init-fn name nrepl-start nrepl-port-file dev] :as opts}]
  (when-not init-fn
    (util/warn "No :init-fn specified, no app initialization will be performed in-container.\n"))
  (let [env (boot/get-env)
        _ (ensure-dir (:target-path env))
        war-path (pod/call-in* @pod
                   ['boot.immutant.in-pod/build-war
                    (-> env
                      (select-keys [:dependencies :repositories :local-repo :offline? :mirrors :proxy :target-path])
                      (merge opts)
                      (assoc
                        :nrepl-port-file (when nrepl-port-file (.getAbsolutePath nrepl-port-file))
                        :name        (or name "project")
                        :nrepl-start (if (contains? opts :nrepl-start) nrepl-start dev)
                        :classpath   (when dev (gen-classpath env))
                        :uberjar     (when-not dev (gen-uberjar env))))])]
    (util/info
      (format "Immutant war written to %s\n" war-path))))

(defn ensure-wildfly-home [wf-home-option]
  (if-let [home (or wf-home-option (System/getenv "WILDFLY_HOME"))]
    (if (.exists (io/file home))
      home
      (throw (Exception. (format "WildFly home '%s' does not exist." home))))
    (throw (Exception. "No WildFly home specified. Specify via --wildfly-home or $WILDFLY_HOME."))))

(deftask immutant-war
  "Creates an Immutant war."
  [i init-fn         FN   sym   "The 'main' function to call on deploy [nil]"
   d dev                  bool  "Generate a 'dev' war [false]"
   c context-path    PATH str   "Deploy to this context path [nil]"
   v virtual-host    HOST [str] "Deploy to the named host defined in the WildFly config [nil]"
   o destination     DIR  str   "Write the generated war to DIR [(:target-path (get-env))]"
   n name            NAME str   "Override the name of the war (sans the .war suffix) [\"project\"]"
   r resource-path   PATH [str] "Paths to file trees to include in the top level of the war [nil]"
   _ nrepl-host      HOST str   "Host for nrepl to bind to [\"localhost\"]"
   _ nrepl-port      PORT int   "Port for nrepl to bind to [0]"
   _ nrepl-port-file FILE file  "File to write actual nrepl port to [nil]"
   _ nrepl-start          bool  "Request nrepl to start [dev]"
   _ nrepl-options   CODE code  "Repl options map [{}]"]
  (boot/with-pre-wrap fileset
    (war-machine *opts*)
    fileset))

(deftask immutant-test
  "Runs a project's tests inside WildFly."
  [c cluster            bool  "Deploy the test application to a cluster [false]"
   d debug              bool  "Start the server with debugging enabled [false]"
   w wildfly-home  PATH str   "Use the WildFly at PATH [(System/getenv \"WILDFLY_HOME\")]"
   o port-offset   AMT  int   "Offset the WildFly network ports [67]"
   i init-fn       FN   sym   "The 'main' function to call on deploy [nil]"
   r resource-path PATH [str] "Paths to file trees to include in the top level of the war [nil]"]
  ;; TODO: support test selection? log level?
  (let [tmp (boot/temp-dir!)
        war-name "project-test"
        war-file (io/file tmp (str war-name ".war"))]
    (boot/with-pre-wrap fileset
      (let [wildfly-home' (ensure-wildfly-home wildfly-home)
            port-file (io/file tmp "nrepl-port")
            {:keys [resource-paths source-paths target-path]} (boot/get-env)
            isolation-dir (str target-path "/isolated-wildfly")]
        (war-machine
          (assoc (select-keys *opts* [:init-fn :resource-path])
            :dev true
            :nrepl-start true
            :nrepl-port-file port-file
            :destination (.getAbsolutePath tmp)
            :name war-name))
        (util/info
          (format
            "Running tests inside WildFly (log output available in %s/isolated-wildfly/%s/log/server.log)...\n"
            target-path
            (if cluster "domain/servers/*" "standalone")))
        (when-not
            (pod/call-in* @pod
              ['boot.immutant.in-pod/run-tests
               (assoc *opts*
                 :isolation-dir isolation-dir
                 :dirs (into resource-paths source-paths)
                 :port-file (.getAbsolutePath port-file)
                 :war-file (.getAbsolutePath war-file)
                 :wildfly-home wildfly-home')])
          (throw (Exception. "Tests failed or errored."))))
      fileset)))