## `konserve-cloud-storage`

```clojure
 com.literalco/konserve-cloud-storage
 {:git/url "https://github.com/The-Literal-Company/konserve-cloud-storage.git"
  :git/sha "570f8f18044ec54ed94c47020c069cebebbaf1f2"}
```

<hr>

### usage

+ `konserve-cloud-storage/connect-bucket-store` will create buckets if they don't exist. 

+ Stores are a flat collection of blobs kept in folders. Deleting a store means deleting konserve blobs but ignoring the folder.

+ Optionally pass `:client` to override default service client.

```clojure
(require '[konserve-cloud-storage.core :as kcs])

(def spec
  {:bucket   "my-unique-bucket"
   :store-id "konserve-store-id"
   :location "US-EAST1"})
   
(def bucket-store (kcs/connect-bucket-store spec :opts {:sync? true}))
```

### links
+ [konserve](https://github.com/replikativ/konserve)
+ [konserve-s3](https://github.com/replikativ/konserve-s3)
+ [cloud.google.com/java/docs/reference/google-cloud-storage/latest](https://cloud.google.com/java/docs/reference/google-cloud-storage/latest/com.google.cloud.storage.Storage)
