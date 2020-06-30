(ns api.core
  (:require [clojure.spec.alpha :as s]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.coercion.spec]
            [muuntaja.middleware :as mw]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(s/def :event/name (s/and string? seq))
(s/def :task/uri (s/and string? seq))
(s/def :task/title (s/and string? seq))
(s/def :todo/task-event (s/keys :req [:event/name :task/uri :task/title]))

;;; --------------------------- state reducer --------------------------------

(defn apply-event
  "Reduces a valid application state from a vector of events. The events
  must be sorted in order of when the events were received."
  [tasks event]
  (if (s/invalid? (s/conform :todo/task-event event))
    tasks
    (case (:event/name event)
      "task-added"
      (->> (select-keys event [:task/uri :task/title])
           (conj (remove #(= (:task/uri event) (:task/uri %)) tasks)))
      "task-completed"
      (remove #(= (:task/uri %) (:task/uri event)) tasks) tasks)))

;;; --------------------------- HTTP server ----------------------------------

(defn task-event-handler
  "Sends the received event to the event-store and responds to the request."
  [event-name send]
  (fn [request]
    (send {:event/name event-name
           :task/uri   (get-in request [:body-params :uri])
           :task/title (get-in request [:body-params :title])})
    {:status 201}))

(defn app
  "Listens to HTTP requests and routes them to the correct handlers.

  `event-store` is a map of:
   - a :send function. It receives an event (map with at least :event/name
   and any other keywords related to the event).
   - an :events function. It responds with the list of events in the store."
  [event-store]
  (let [{send-to-store :send, events :events} event-store]
    (ring/ring-handler
      (ring/router
        [["/tasks"
          {:get (fn [_] {:status 200 :body (reduce apply-event [] (events))})}]
         ["/tasks/added"
          {:post       (task-event-handler "task-added" send-to-store)
           :parameters {:body {:uri string? :title string?}}}]
         ["/tasks/completed"
          {:post       (task-event-handler "task-completed" send-to-store)
           :parameters {:body {:uri string? :title string?}}}]]
        {:data
         {:coercion   reitit.coercion.spec/coercion
          :middleware [mw/wrap-format
                       rrc/coerce-exceptions-middleware
                       rrc/coerce-request-middleware
                       rrc/coerce-response-middleware]}})
      (ring/create-default-handler))))

(defn in-memory-event-store []
  (let [events (atom [])]
    {:send   (fn [event]
               (when (s/valid? (s/keys :req [:event/name]) event)
                 (swap! events conj event)))
     :events (fn [] @events)}))

(defn -main [& args]
  (jetty/run-jetty (app (in-memory-event-store)) {:port 3000}))
