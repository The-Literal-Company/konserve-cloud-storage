(ns konserve-cloud-storage.core
  (:require [clojure.test :refer :all]
            [konserve.impl.defaults :as defaults]
            [konserve.impl.storage-layout :as impl :refer [PBackingLock]]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [superv.async :refer [go-try- <?-]]
            [taoensso.timbre :as log :refer [info trace]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Arrays]
           [com.google.cloud.storage Blob
                                     BlobId
                                     BlobInfo Bucket
                                     BucketInfo
                                     Storage
                                     Storage$BlobGetOption
                                     Storage$BlobListOption
                                     Storage$BlobSourceOption
                                     Storage$BlobTargetOption
                                     Storage$BucketGetOption
                                     Storage$BucketTargetOption
                                     Storage$CopyRequest
                                     StorageOptions]))

(def ^:dynamic *default-bucket* "konserve")
(def ^:dynamic *output-stream-buffer-size* (* 1024 1024))
(def ^:dynamic *deletion-batch-size* 1000)

(defn ^BlobId blob-id
  ([bucket blob-store-path]
   (BlobId/of bucket blob-store-path))
  ([bucket store-path blob-key]
   (BlobId/of bucket (str store-path "/" blob-key))))

(defn write-blob
  [client blob-id bytes]
  (let [blob-info (.build (BlobInfo/newBuilder ^BlobId blob-id))
        opts (into-array Storage$BlobTargetOption [])]
    (.create client ^BlobInfo blob-info #^bytes bytes #^Storage$BlobTargetOption opts)))

(defn read-blob [client blob-id]
  (let [opts (into-array Storage$BlobSourceOption [])]
    (.readAllBytes client blob-id opts)))

(defrecord CloudStorageBlob
  [client bucket store-path blob-key data fetched-object]
  impl/PBackingBlob
  (-sync [_ env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (let [{:keys [header meta value]} @data
              baos (ByteArrayOutputStream. *output-stream-buffer-size*)]
          (if (and header meta value)
            (do
              (.write baos #^bytes header)
              (.write baos #^bytes meta)
              (.write baos #^bytes value)
              (write-blob client (blob-id bucket store-path blob-key) (.toByteArray baos))
              (.close baos)
              (reset! data {}))
           (throw (ex-info "Updating a row is only possible if header, meta and value are set." {:data @data})))))))
  (-close [_ env]
    (if (:sync? env) nil (go-try- nil)))
  (-get-lock [_ env]
    (if (:sync? env) true (go-try- true)))                       ;; May not return nil, otherwise eternal retries
  (-read-header [_ env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        ;; first access is always to header, after it is cached
        (when-not @fetched-object
          (reset! fetched-object (read-blob client (blob-id bucket store-path blob-key))))
        (Arrays/copyOfRange ^bytes @fetched-object (int 0) (int impl/header-size)))))
  (-read-meta [_ meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (Arrays/copyOfRange ^bytes @fetched-object (int impl/header-size) (int (+ impl/header-size meta-size))))))
  (-read-value [_ meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (let [obj ^bytes @fetched-object]
          (Arrays/copyOfRange obj (int (+ impl/header-size meta-size)) (int (alength obj)))))))
  (-read-binary [_ meta-size locked-cb env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (let [obj ^bytes @fetched-object]
          (<?-
            (locked-cb {:size (- (alength obj) (+ impl/header-size meta-size))
                        :input-stream
                        (ByteArrayInputStream.
                          (Arrays/copyOfRange obj (int (+ impl/header-size meta-size)) (int (alength obj))))}))))))
  (-write-header [_ header env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (swap! data assoc :header header))))
  (-write-meta [_ meta env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (swap! data assoc :meta meta))))
  (-write-value [_ value _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (swap! data assoc :value value))))
  (-write-binary [_ _meta-size blob env]
    ;; TODO offer blob stream
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (swap! data assoc :value blob)))))

(defn ^Boolean delete-blob
  [client bucket store-path blob-key]
  (let [blob-id (blob-id bucket store-path blob-key)
        opts (into-array Storage$BlobSourceOption [])]
    (.delete client blob-id opts)))

(defn ^Boolean delete-many-blobs
  [client bucket blob-store-paths]
  (let [blob-ids (map (partial blob-id bucket) blob-store-paths)]
    (.delete client #^BlobId (into-array BlobId blob-ids))))

(defn ^Blob blob-exists?
  [client bucket store-path blob-key]
  (let [blob-id (blob-id bucket store-path blob-key)
        opts (into-array Storage$BlobGetOption [])]
    (.get client blob-id opts)))

(defn ^Blob copy-blob
  [client bucket store-path from-blob-key to-blob-key]
  (let [from-blob-id (blob-id bucket store-path from-blob-key)
        to-blob-id (blob-id bucket store-path to-blob-key)
        copy-request (Storage$CopyRequest/of ^BlobId from-blob-id ^BlobId to-blob-id)
        copy-writer (.copy client copy-request)]
    (.getResult copy-writer)))

(defn get-bucket
  [client bucket-name]
  (let [opts (into-array Storage$BucketGetOption [])]
    (.get client bucket-name opts)))

(defn ^Bucket create-bucket [client location bucket]
  (let [bucket-info (-> (BucketInfo/newBuilder bucket)
                        (.setLocation location)
                        (.build))
        opts (into-array Storage$BucketTargetOption [])]
    (.create client bucket-info opts)))

(defn list-objects
  [client bucket store-path]
  (let [bucket (.get client bucket (into-array Storage$BucketGetOption []))
        opts [(Storage$BlobListOption/pageSize 100)
              (Storage$BlobListOption/includeFolders true)
              (Storage$BlobListOption/delimiter "/")
              (Storage$BlobListOption/prefix (str store-path "/"))]
        blobs (.list bucket (into-array Storage$BlobListOption opts))]
    (seq (.iterateAll blobs))))

(extend-protocol PBackingLock
  Boolean
  (-release [_ env]
    (if (:sync? env) nil (go-try- nil))))

(defrecord CloudStorageBucket [client location bucket store-path]
  impl/PBackingStore
  (-create-blob [this blob-key env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (CloudStorageBlob. client bucket store-path blob-key (atom {}) (atom nil)))))
  (-delete-blob [_ blob-key env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (delete-blob client bucket store-path blob-key))))
  (-blob-exists? [_ blob-key env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (blob-exists? client bucket store-path blob-key))))
  (-copy [_ from-key to-key env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try- (copy-blob client bucket store-path from-key to-key))))
  (-atomic-move [_ from-key to-key env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (copy-blob client bucket store-path from-key to-key)
        (delete-blob client bucket store-path from-key))))
  (-migratable [_ _key _store-key env]
    (if (:sync? env) nil (go-try- nil)))
  (-migrate [_ _migration-key _key-vec _serializer _read-handlers _write-handlers env]
    (if (:sync? env) nil (go-try- nil)))
  (-create-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (when-not (get-bucket client bucket)
          (log/info (str "creating bucket " bucket))
          (create-bucket client location bucket)))))
  (-sync-store [_ env]
    (if (:sync? env) nil (go-try- nil)))
  (-delete-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (when (get-bucket client bucket)
          (let [blobs (list-objects client bucket store-path)
                keys (filter (fn [key]
                               (and (.startsWith key store-path)
                                    (or (.endsWith key ".ksv")
                                        (.endsWith key ".ksv.new")
                                        (.endsWith key ".ksv.backup"))))
                             (map #(.getName %) blobs))]
            (doseq [keys (->> keys
                           (partition *deletion-batch-size* *deletion-batch-size* []))]
              (delete-many-blobs client bucket keys)))
          (.close client)))))
  (-keys [_ env]
    (async+sync (:sync? env) *default-sync-translation*
      (go-try-
        (let [blobs (list-objects client bucket store-path)
              keys (map #(.getName %) blobs)]
          (->> keys
              (filter (fn [key]
                        (and (.startsWith key store-path)
                             (or (.endsWith key ".ksv")
                                 (.endsWith key ".ksv.new")
                                 (.endsWith key ".ksv.backup")))))
               ;; remove store-id prefix
               (map #(subs % (inc (count store-path))))))))))

(comment
  {:bucket   "konserve-demo"
   ;;:project-id optional
   :store-path "test-store"
   :location "US-EAST1"})

(defn cloud-storage-client
  [{:keys [client project-id]}]
  (or client
      (let [builder (StorageOptions/newBuilder)]
        (when project-id
          (.setProjectId builder project-id))
        (.getService (.build builder)))))

(defn spec->store-path
  [{:keys [store-path store-id]}]
  (or store-path store-id
      (throw (Exception. "expected store path in store-spec as :store-path or :store-id"))))

(defn connect-bucket-store [spec & {:keys [opts] :as params}]
  (assert (string? (:bucket spec)))
  (assert (string? (:location spec)))
  (let [client (cloud-storage-client spec)
        store-path (spec->store-path spec)
        backing (CloudStorageBucket. client (:location spec) (:bucket spec) store-path)
        config (merge {:opts               opts
                       :config             {:sync-blob? true
                                            :in-place? false
                                            :lock-blob? true}
                       :default-serializer :FressianSerializer
                       :buffer-size        (* 1024 1024)}
                      (dissoc params :opts :config))]
    (defaults/connect-default-store backing config)))

(defn release [store env]
  (async+sync (:sync? env) *default-sync-translation*
    (go-try- (.close ^Storage (:client (:backing store))))))

(defn delete-store [spec & {:keys [opts]}]
  (assert (string? (:bucket spec)))
  (assert (string? (:location spec)))
  (assert (string? (or (:store-path spec) (:store-id spec))))
  (let [complete-opts (merge {:sync? true} opts)
        store-path (spec->store-path spec)
        backing (CloudStorageBucket. (cloud-storage-client spec) (:location spec) (:bucket spec) store-path)]
    (impl/-delete-store backing complete-opts)))
