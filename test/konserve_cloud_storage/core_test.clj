(ns konserve-cloud-storage.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!! go] :as async]
            [konserve-cloud-storage.core :as kcs]
            [konserve-compliance-tests.core :refer [compliance-test]]
            [konserve-compliance-tests.cache :as ct]
            [konserve-compliance-tests.encryptor :as et]
            [konserve-compliance-tests.gc :as gct]
            [konserve-compliance-tests.serializers :as st]
            [taoensso.timbre :as log]))

(def KONSERVE_TEST_BUCKET
  (or (System/getenv "KONSERVE_TEST_BUCKET")
      (throw (Exception. "set KONSERVE_TEST_BUCKET in the environment"))))

(deftest cloud-storage-compliance-test
  (log/info "starting cloud-storage-compliance-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "compliance_test_store"}
        _(kcs/delete-store spec)
        store  (kcs/connect-bucket-store spec :opts {:sync? true})]
    (testing "Compliance test with default config."
      (compliance-test store))))

;#!============
;#! Cache tests

(deftest cache-PEDNKeyValueStore-test
  (log/info "starting cache-PEDNKeyValueStore-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "cache_test_store"}
        _(kcs/delete-store spec)
        store (kcs/connect-bucket-store spec :opts {:sync? true})]
    (<!! (ct/test-cached-PEDNKeyValueStore-async store))))

(deftest cache-PKeyIterable-test
  (log/info "starting cache-PKeyIterable-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "cache_test_store"}
        _(kcs/delete-store spec)
        store (kcs/connect-bucket-store spec :opts {:sync? true})]
    (<!! (ct/test-cached-PKeyIterable-async store))))

(deftest cache-PBin-test
  (log/info "starting cache-PBin-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "cache_test_store"}
        _(kcs/delete-store spec)
        store (kcs/connect-bucket-store spec :opts {:sync? true})
        f (fn [{:keys [input-stream]}]
            (async/to-chan! [input-stream]))]
    (<!! (ct/test-cached-PBin-async store f))))

#!============
#! GC tests

(deftest gc-test
  (log/info "starting gc-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "gc_test_store"}
        _(kcs/delete-store spec)
        store (kcs/connect-bucket-store spec :opts {:sync? true})]
    (<!! (gct/test-gc-async store))))

#!==================
#! Serializers tests

(deftest fressian-serializer-test
  (log/info "starting fressian-serializer-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "serializers_test_store"}]
    (<!! (st/test-fressian-serializers-async spec
                                             kcs/connect-bucket-store
                                             (fn [p] (go (kcs/delete-store p)))
                                             (fn [{:keys [input-stream]}]
                                               (async/to-chan! [input-stream]))))))

(deftest CBOR-serializer-test
  (log/info "starting CBOR-serializer-test")
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "serializers_test_store"}]
    (st/cbor-serializer-test spec
                             kcs/connect-bucket-store
                             (fn [p] (go (kcs/delete-store p))))))

#!==================
#! Encryptor tests

(deftest encryptor-sync-test
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "encryptor_test_store"}]
    (et/sync-encryptor-test spec
                            kcs/connect-bucket-store
                            (fn [p] (go (kcs/delete-store p))))))

(deftest encryptor-async-test
  (let [spec {:bucket     KONSERVE_TEST_BUCKET
              :store-path "encryptor_test_store"}]
    (<!! (et/async-encryptor-test spec
                                  kcs/connect-bucket-store
                                  (fn [p] (go (kcs/delete-store p)))))))
