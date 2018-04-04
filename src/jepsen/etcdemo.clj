(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as v]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [nemesis :as nemesis]
             [independent :as independent]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn parse-long
  [s]
  (when s (Long/parseLong s)))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 2379))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn db
  "Etcd DB for a particular version."
  [version]
  (reify db/DB

    (setup! [_ test node]
      (info node "installing etcd" version)
      (c/su
       (let [url (str "file:///opt/jepsen_db/etcd-" version "-linux-amd64.tar.gz")]
         (cu/install-archive! url dir))
       (cu/start-daemon!
        {:logfile logfile
         :pidfile pidfile
         :chdir   dir}
        binary
        :--log-output                   :stderr
        :--name                         (name node)
        :--listen-peer-urls             (peer-url node)
        :--listen-client-urls           (client-url node)
        :--advertise-client-urls        (client-url node)
        :--initial-cluster-state        :new
        :--initial-advertise-peer-urls  (peer-url node)
        :--initial-cluster              (initial-cluster test))

       ;; allow time to elect leader
       (Thread/sleep 15000)))

    (teardown! [_ test node]
      (info node "tearing down etcd")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))


    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (client-url node) {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (try+
       (case (:f op)
         ;; {:quorum? true} fixes etcd consistency issue
         :read (let [value (-> conn
                               (v/get k {:quorum? true})
                               (parse-long))]
                 (assoc op :type :ok :value (independent/tuple k value)))

         :write (do (v/reset! conn k v)
                    (assoc op :type :ok))

         :cas (let [[old new] v]
                (assoc op :type (if (v/cas! conn k old new)
                                  :ok
                                  :fail))))

       (catch java.net.SocketTimeoutException _
         (assoc op
                :type (if (= :read (:f op)) :fail :info)
                :error :timeout))

       (catch [:errorCode 100] _
         (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]))

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh, :concurrency, ...),
  constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "etcd"
          :os debian/os
          :db (db "v3.1.5")
          :client (Client. nil)
          :nemesis (nemesis/partition-random-halves)
          :model (model/cas-register)
          :checker (checker/compose
                    {:perf (checker/perf)
                     :indep (independent/checker
                             (checker/linearizable))})
          :generator (->> (independent/concurrent-generator
                           10
                           (range)
                           (fn [k]
                             (->> (gen/mix [r w cas])
                                  (gen/stagger 1/10)
                                  (gen/limit 100))))
                          (gen/nemesis
                           (gen/seq (cycle
                                     [(gen/sleep 5)
                                      {:type :info :f :start}
                                      (gen/sleep 5)
                                      {:type :info :f :stop}])))
                          (gen/time-limit (:time-limit opts)))}))

(defn -main
  "Handles command line arguments. Can run test or a web server for browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn etcd-test}) args))
