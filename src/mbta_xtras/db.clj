(ns mbta-xtras.db
  (:require [monger.collection :as mc]
            [monger.core :as mg]
            [monger.operators :refer [$near]]
            [com.stuartsierra.component :as component]

            [mbta-xtras.db :as db]
            [mbta-xtras.gtfs :as gtfs]
            [mbta-xtras.realtime :as rt]
            [mbta-xtras.utils :as $]

            [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.string :as str]
            [taoensso.timbre :refer [error info]]
            [clojure.set :as set]))


(defonce agencies (atom nil))
(defonce transfers (atom nil))
(defonce calendar (atom nil))
;; Map of dates -> canceled service-ids
(defonce no-service (atom nil))
;; Map of dates -> extra service-ids
(defonce xtra-service (atom nil))

(defn make-point [m lat-k lon-k]
  {:type "Point",
   :coordinates [(Double/parseDouble (lon-k m))
                 (Double/parseDouble (lat-k m))]})

(defn near-query
  ([lat lon & [max-dist]]
   {$near {"$geometry" {:type "Point"
                        :coordinates [lon lat]}
           "$maxDistance" (or max-dist 50)}}))

; Stops:
(defn process-stop [stop]
  (-> stop
      (assoc :coords (make-point stop :stop-lat :stop-lon))
      (update :stop-lat #(Double/parseDouble %))
      (update :stop-lon #(Double/parseDouble %))))

(defn find-closest-stop [db lat lon]
  (mc/find-one-as-map db "stops" {:coords (near-query lat lon)}))

(defn double-key [k] #(Double/parseDouble (k %)))

;; Stop times
(defn process-stop-time [x]
  (update x :stop-sequence $/->int))

(defn minc [a b]
  (if (neg? (compare a b)) a b))

(defn maxc [a b]
  (if (pos? (compare a b)) a b))

(defn save-api-trip! [db {:keys [stops] :as api-trip}]
  (mc/insert-batch db "stop-times" stops)
  (let [[start end] (reduce (fn [[min-arrival max-departure] [arrival departure]]
                              [(minc arrival min-arrival) (maxc departure max-departure)])
                            (map (juxt :scheduled-arrival :scheduled-departure)
                                 stops))]
    (mc/insert db "trips" (-> api-trip
                              (dissoc :stops)
                              (assoc :start-time start
                                     :end-time end)))))

(defn stop-times-for-trip [db trip-id]
  (let [stop-times (mc/find-maps db "stop-times" {:trip-id trip-id}
                                 {:_id 0, :stop-id 1, :stop-sequence 1, :arrival-time 1,
                                  :departure-time 1})]
    (if (seq stop-times)
      stop-times

      (when-let [api-trip (rt/get-trip trip-id)]
        (save-api-trip! db api-trip)
        (:stops api-trip)))))

(defn drop-trip-stops! [db]
  (mc/drop db "trip-stops"))

;; Shapes
(defn make-shapes [shape-points]
  (map (fn [points]
         {:shape-id (:shape-id (first points))
          :path {:type "LineString"
                 :coordinates (mapv (juxt (double-key :shape-pt-lon)
                                          (double-key :shape-pt-lat))
                                    points)}})
       (partition-by :shape-id shape-points)))


(def process-trip identity)


(defmulti process-csv! (fn [[x] _] x))
(defmethod process-csv! :default [_ _] nil)
(defmethod process-csv! "stops.txt"
  [[_ rdr] db]
  (mc/insert-batch db "stops" (map process-stop (gtfs/reader-maps rdr))))

(defmethod process-csv! "routes.txt"
  [[_ rdr] db]
  (mc/insert-batch db "routes" rdr))

(defmethod process-csv! "stop_times.txt"
  [[_ rdr] db]
  ;; Add the stops times in batches
  (doseq [stop-times-group (->> (gtfs/reader-maps rdr)
                                (map process-stop-time)
                                (partition 20000))]
    (mc/insert-batch db "stop-times" stop-times-group)))

(defmethod process-csv! "trips.txt"
  [[_ rdr] db]
  (mc/insert-batch db "trips" (gtfs/reader-maps rdr)))

(defn process-service [service]
  (reduce (fn [service k]
            (assoc service k (= (k service) "1")))
          service
          $/day-keywords))

(defmethod process-csv! "calendar.txt"
  [[_ rdr] _db]
  (reset! calendar (into {} (map (fn [service]
                                   [(:service-id service)
                                    (process-service service)])
                                 (gtfs/reader-maps rdr)))))

(defn process-service-exceptions [service-exceptions]
  (reduce (fn [m exc]
            (update-in m [(:exception-type exc)
                          (:date exc)]
                       conj (:service-id exc)))
          {}
          service-exceptions))

(defmethod process-csv! "calendar_dates.txt"
  [[_ rdr] _db]
  (let [excs (process-service-exceptions (gtfs/reader-maps rdr))]
    (reset! no-service (get excs "2"))
    (reset! xtra-service (get excs "1"))))

(defmethod process-csv! "agency.txt"
  [[_ rdr] _db]
  (reset! agencies ($/index-by :agency-id (gtfs/reader-maps rdr))))

(defmethod process-csv! "transfers.txt"
  [[_ rdr] _db]
  (reset! transfers (gtfs/reader-maps rdr)))

;; Ignore calendar_dates.txt, fare_attributes.txt, fare_rules.txt,
;; frequencies.txt, shapes.txt

(defn trip-ends [db]
  (mc/aggregate db "stop-times"
                [{:$group {:_id "$trip-id",
                           :start {:$min "$departure-time"}
                           :end {:$max "$arrival-time"}}}]))

(defn post-update [db]
  ;; Record the trip start and stop times.
  (doseq [{trip-id :_id, s :start, e :end} (trip-ends db)]
    (mc/update db "trips" {:trip-id trip-id} {:$set {:start-time s
                                                     :end-time e}})))

(defn do-update! [db]
  (doseq [[file-name _csv :as gtfs-file] (gtfs/iterate-gtfs-files)]
    (info "Reading updates from" file-name)
    (try
      (process-csv! gtfs-file db)
      (catch Exception exc
        (error "Error while processing" file-name exc))))
  (post-update db))

(defn slurp-run-info []
  (try
    (read-string (slurp "resources/run_info.clj"))
    (catch Exception _
      nil)))

(defn update-loop
  "Start a feed updates channel. Every time it reports an update, iterate over
  the contents of the GTFS zip and process the files."
  [db]
  (let [updates (gtfs/feed-updates)]
    (go-loop []
      ;; The updates channel receives a non-nil message each time the feed is
      ;; updated.
      (when-let [info (<! updates)]
        (when (not= (:feed-version (slurp-run-info))
                    (:feed-version info))
          (do-update! db)
          (spit "resources/run_info.clj" (pr-str info)))
        (recur)))
    updates))


(defn make-re [s]
  (re-pattern (str "(?<=\\b|^)"
                   (str/replace s #"[\[\\.?\^\(]" "\\\\$0"))))


(defn build-stop-query [{:keys [q lat lon dist]}]
  (cond-> {}
    q (assoc :stop-name (make-re q))

    (and lat lon) (assoc :coords (near-query
                                  (Double/parseDouble lat)
                                  (Double/parseDouble lon)
                                  (Integer/parseInt dist)))))

(defn find-stops [db params]
  (mc/find-maps db "stops"
                (build-stop-query params)
                {:_id 0}))

(defn build-trip-query [{:keys []}])

(defn find-trips [db params]
  (mc/find-maps db "trips" (build-trip-query params)))

(defn trip-runs-on?
  ;; TODO
  "Determines if the trip `trip-id` runs on the date `dt`."
  [db trip-id dt]
  (let [{:keys [service-id]}
        (mc/find-one-as-map db "trips" {:trip-id trip-id})]))


(defn find-trips-for-stop [db stop-id]
  (mc/distinct db "stop-times" :trip-id {:stop-id stop-id}))

(defn trip-updates [db trip-id trip-start]
  (->> (mc/find-maps db "trip-stops" {:trip-id trip-id
                                      :trip-start trip-start}
                     {:_id 0})
       (sort-by :stop-sequence)))

(defn travel-times [db from-stop]
  (mc/find-maps db "stop-times" {:stop-id from-stop} {:_id 0,
                                                      :trip-id 1
                                                      :stop-sequence 1}))

(defn services-at
  "Calculates a set of service-ids that run on a given LocalDateTime."
  [dt]
  (-> (into #{} (comp (map val)
                      (filter ($/day-keyword dt))
                      (let [d (.toLocalDate dt)]
                        (filter (fn [{:keys [end-date start-date]}]
                                  (and (>= (compare d ($/local-date-for-str
                                                       start-date)) 0)
                                       (<= (compare d ($/local-date-for-str
                                                       end-date)) -1)))))
                      (map :service-id))
            @calendar)
      (set/difference (set (get @no-service ($/date-str dt))))
      (set/union (set (get @xtra-service ($/date-str dt))))))

;; The start and stop times are not stored in trips!
(defn scheduled-trips-at
  "Returns a sequence of trip ids that are scheduled to run at the given time."
  [db dt]
  (let [to-dt (partial $/offset-time dt)]
    (filter
     (fn [{:keys [start-time end-time]}]
       (and start-time end-time
            (>= (compare dt (to-dt start-time)) 0)
            (< (compare dt (to-dt end-time)) 0)))
     (mc/find-maps db "trips" {:service-id {:$in (services-at dt)}}
                   {:_id 0, :block-id 0, :service-id 0, :shape-id 0,
                    :trip-short-name 0, :wheelchair-accessible 0}))))


(defrecord GtfsUpdater []
  component/Lifecycle
  (start [this]
    (assoc this :stop-chan (update-loop (-> this :mongo :db))))

  (stop [this]
    (some-> (:stop-chan this) async/close!)
    (dissoc this :stop-chan)))

(defn make-updater [] (->GtfsUpdater))
