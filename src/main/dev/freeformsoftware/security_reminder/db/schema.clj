(ns dev.freeformsoftware.security-reminder.db.schema
  (:require
   [com.fulcrologic.guardrails.malli.core :refer [>defn >def =>]]
   [malli.core :as m]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Entity schemas
;; =============================================================================

(def Person
  [:map
   [:id :string]
   [:name :string]
   [:email :string]
   [:admin? :boolean]])

(def EventTemplate
  [:map
   [:id :string]
   [:label :string]
   [:day-of-week [:int {:min 1 :max 7}]]
   [:time-label [:enum :morning :afternoon :evening]]
   [:people-required [:int {:min 1}]]])

(def OneOffEvent
  [:map
   [:id :string]
   [:label :string]
   [:date :string]
   [:time-label [:enum :morning :afternoon :evening]]
   [:people-required [:int {:min 1}]]])

(def Absence
  [:map
   [:person-id :string]
   [:event-date :string]
   [:event-template-id {:optional true} :string]
   [:one-off-event-id {:optional true} :string]])

(def InstanceOverride
  [:map
   [:event-date :string]
   [:event-template-id :string]
   [:people-required [:int {:min 1}]]])

(def AssignmentOverride
  [:map
   [:event-date :string]
   [:event-template-id {:optional true} :string]
   [:one-off-event-id {:optional true} :string]
   [:assigned [:vector :string]]])

(def SentNotification
  [:map
   [:person-id :string]
   [:event-date {:optional true} :string]
   [:event-template-id {:optional true} :string]
   [:one-off-event-id {:optional true} :string]
   [:type [:enum :assigned :rescinded :reminder :welcome]]
   [:sent-at :string]
   [:sent-for-reminder-group {:optional true} :int]])

(def ScheduleDb
  [:map
   [:event-templates [:vector EventTemplate]]
   [:one-off-events [:vector OneOffEvent]]
   [:absences [:vector Absence]]
   [:sent-notifications [:vector SentNotification]]
   [:instance-overrides [:vector InstanceOverride]]
   [:assignment-overrides [:vector AssignmentOverride]]
   [:sec-tokens [:map-of :string :string]]
   [:people [:vector Person]]])

(def PlanEntry
  [:map
   [:event-key
    [:map
     [:date :string]
     [:template-id {:optional true} :string]
     [:one-off-id {:optional true} :string]]]
   [:label :string]
   [:date :string]
   [:time-label [:enum :morning :afternoon :evening]]
   [:people-required [:int {:min 1}]]
   [:assigned [:vector :string]]
   [:absent [:vector :string]]
   [:understaffed? :boolean]])

(def Plan
  [:vector PlanEntry])

;; =============================================================================
;; Reusable Guardrails schemas
;; =============================================================================

(>def ::date-str [:re #"^\d{4}-\d{2}-\d{2}$"])
(>def ::iso-datetime [:re #"^\d{4}-\d{2}-\d{2}T"])
(>def ::email [:re #".+@.+\..+"])
(>def ::time-label [:enum :morning :afternoon :evening])

(>def ::person Person)
(>def ::event-template EventTemplate)
(>def ::one-off-event OneOffEvent)
(>def ::absence Absence)
(>def ::plan-entry PlanEntry)
(>def ::schedule-db ScheduleDb)

(>def ::event-key
      [:map [:date ::date-str]
       [:template-id {:optional true} :string]
       [:one-off-id {:optional true} :string]])
(>def ::state
      [:map
       [:schedule-db ::schedule-db]
       [:schedule-plan [:vector ::plan-entry]]
       [:actions vector?]])
;; =============================================================================
;; Event-key conversion utilities
;; Canonical event-key: {:date "..." :template-id "..."} or {:date "..." :one-off-id "..."}
;; Flat storage keys:   {:event-date "..." :event-template-id "..."} or {:event-date "..." :one-off-event-id "..."}
;; =============================================================================

(>defn event-key->flat-keys
       "Convert a canonical event-key to flat storage keys used in absences, overrides, notifications."
       [event-key]
       [::event-key => map?]
       (cond-> {:event-date (:date event-key)}
         (:template-id event-key) (assoc :event-template-id (:template-id event-key))
         (:one-off-id event-key)  (assoc :one-off-event-id (:one-off-id event-key))))

(>defn flat-keys->event-key
       "Convert flat storage keys back to a canonical event-key."
       [flat]
       [[:map [:event-date ::date-str]] => map?]
       (cond-> {:date (:event-date flat)}
         (:event-template-id flat) (assoc :template-id (:event-template-id flat))
         (:one-off-event-id flat)  (assoc :one-off-id (:one-off-event-id flat))))

(>defn flat-record-matches-event-key?
       "Check if a flat-keyed record (absence, notification, etc.) matches a canonical event-key."
       [record event-key]
       [map? ::event-key => boolean?]
       (and (= (:event-date record) (:date event-key))
            (boolean
             (or (and (:template-id event-key)
                      (= (:event-template-id record) (:template-id event-key)))
                 (and (:one-off-id event-key)
                      (= (:one-off-event-id record) (:one-off-id event-key)))))))

(>defn validate-schedule-db!
       "Validates schedule-db data against schema. Throws on invalid data."
       [data]
       [map? => ::schedule-db]
       (when-not (m/validate ScheduleDb data)
         (throw (ex-info "Invalid schedule-db data"
                         {:errors (m/explain ScheduleDb data)})))
       data)
