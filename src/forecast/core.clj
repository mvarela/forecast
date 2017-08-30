(ns forecast.core
  (:require [forecast.keys :as apikeys]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clojure.core.memoize :as memo]))

(defn get-city-coords
  "Gets a city's lat-long coordinates from the Google Maps API"
  [city, country]
  (let  [response  (client/get "https://maps.googleapis.com/maps/api/geocode/json?"
                               {:query-params {:key apikeys/gmaps-key
                                               :address (str city ",+" country)}})
         jresp (json/read-str (:body response) :key-fn keyword)
         coords (get-in jresp [:results 0 :geometry :location])]
    {:lat (:lat coords) :lng (:lng coords)}))

(def get-city-coords-m
  "Caching (memoized) version of get-city-coords"
  (memo/lru get-city-coords {} :lru/threshold 100))

;; (defmacro let-map [vars & forms]
;;   `(eval (list 'let (->> ~vars keys
;;                          (map (fn [sym#] [(-> sym# name symbol) (~vars sym#)]))
;;                          (apply concat) vec)
;;                '~(conj forms 'do))))

(defn get-photo-url
  "Build a flickr url from a search result json object for a photo"
  [photo]
  (let [[farm server secret id] (map photo [:farm :server :secret :id])]
    (str "https://farm" farm ".staticflickr.com/" server "/" id "_" secret "_b.jpg")))

(defn get-picture
  "Gets a CC-licensed picture of a city, at random, from Flickr"
  [city, country]
  (let[jresp (get-picture-choices-m city country)
       photo (nth (get-in jresp [:photos :photo]) (rand (count (:photos jresp))))]
    (get-photo-url photo)))


(defn get-picture-choices
  "Cache-able way to get a bunch of picture data from Flickr's API, given a city
  and country name"
  [city country]
  (let [max-pics 10
        params {:method "flickr.photos.search"
                :api_key apikeys/flickr-key
                :safe_search 1    ;; no smut
                :content_type 1   ;; only photos
                :text (str city " " country)
                :sort  "interestingness-desc"
                :per_page max-pics
                :page 1
                :nojsoncallback 1 ;; remove non-json crap
                :format "json"}
        response (client/get "https://api.flickr.com/services/rest/" {:query-params params})]
        (json/read-str (:body  response) :key-fn keyword)))

(def get-picture-choices-m
  "Memoized version of picture data fetcher"
  (memo/fifo get-picture-choices {} :fifo/threshold 100))


(defn get-forecast
  "Gets weather forecast for the requested lat-lng coordinates, as a JSON object"
  [lat lng]
  (let [url (str "https://api.darksky.net/forecast/" apikeys/darksky-key "/" lat "," lng)
        response (client/get url {:query-params {:units "si"}})
        jresp (json/read-str (:body response) :key-fn keyword)
        ]
    jresp))

(def get-forecast-m
  "Caching (memoized with a TTL of 1h) version of get-forecast"
  (memo/ttl get-forecast {} :ttl/threshold 3600000))

(defn do-forecast
  [city country]
  (let [coords (future (get-city-coords-m city country))
        url (future (get-picture city country))
        lat (:lat @coords)
        lng (:lng @coords)
        forecast (get-forecast-m lat lng )]
    {:forecast forecast :pic-url @url}))


