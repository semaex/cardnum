(ns game.utils
  (:require [clojure.string :refer [split-lines split join]]))

(declare pluralize)

(def cid (atom 0))

(defn make-cid []
  (swap! cid inc))

(defn abs [n] (max n (- n)))

(defn safe-zero?
  "`zero?` throws up on non numbers, so this is a safe version."
  [n]
  ((fnil zero? 1) n))

(defn safe-inc-n
  "Helper function to safely update a value by n. Returns a function to use with `update` / `update-in`"
  [n]
  (partial (fnil + 0 0) n))

(defn sub->0
  "Helper function for use in `update` or `update-in` to subtract for a value, to a minimum of 0."
  [n]
  #(max 0 (- % n)))

(defn clean-forfeit
  "Takes a flat :forfeit in costs and adds a cost of 1.
  Ignores cost vectors with an even count as these have forfeit value included"
  [costs]
  (let [fcosts (flatten costs)]
  (if (odd? (count fcosts))
    (replace {[:forfeit] [:forfeit 1],
              :forfeit [:forfeit 1]} fcosts)
    costs)))

(defn merge-costs
  "This combines costs from a number of sources in the game into a single cost per type
  Damage is not merged as it needs to be invidual.  Needs augmention more than net-damage appears"
  [costs]
  (let [fc (partition 2 (flatten (clean-forfeit costs)))
        jc (filter #(not= :net-damage (first %)) fc)
        dc (filter #(= :net-damage (first %)) fc)
        reduce-fn (fn [cost-map cost]
                    (let [[cost-type value] cost
                          old-value (get cost-map cost-type 0)]
                      (assoc cost-map cost-type (+ old-value value))))]
    (vec (map vec (concat (reduce reduce-fn {} jc) dc)))))

(defn remove-once [pred coll]
  (let [[head tail] (split-with (complement pred) coll)]
    (vec (concat head (rest tail)))))

(defn has?
  "Checks the string property of the card to see if it contains the given value"
  [card property value]
  (when-let [p (property card)]
    (> (.indexOf p value) -1)))

(defn card-is?
  "Checks the property of the card to see if it is equal to the given value,
  as either a string or a keyword"
  [card property value]
  (let [cv (property card)]
    (cond
      (or (keyword? cv) (and (string? value) (string? cv))) (= value cv)
      (and (keyword? value) (string? cv)) (= value (keyword (.toLowerCase cv)))
      :else (= value cv))))

(defn zone
  "Associate the specified zone to each item in the collection.
  Zone can be a singleton or a sequential collection"
  [zone coll]
  (let [dest (if (sequential? zone) (vec zone) [zone])]
    (map #(assoc % :zone dest) coll)))

(defn to-keyword [string]
  (cond

    (= "[Credits]" string)
    :credit

    (string? string)
    (keyword (.toLowerCase string))

    :else
    string))

(defn capitalize [string]
  (str (Character/toUpperCase (first string)) (subs string 1)))

(defn costs->symbol
  "Used during steal to print challenger prompt for payment"
  [costs]
  (join ", " (map #(let [key (first %) value (last %)]
                     (case key
                       :credit (str value " [Credits]")
                       :click (reduce str (for [i (range value)] "[Click]"))
                       :mill (str value " card mill")
                       :hazard (str value " placed hazard")
                       :shuffle-placed-to-stack (str "shuffling " value " placed "
                                                        (pluralize "card" value) " into the stack")
                       (str value (str key)))) (partition 2 (flatten costs)))))

(defn vdissoc [v n]
  (vec (concat (subvec v 0 n) (subvec v (inc n)))))

(defn distinct-by [f coll]
  (letfn [(step [xs seen]
            (lazy-seq (when-let [[x & more] (seq xs)]
                        (let [k (f x)]
                          (if (seen k)
                            (step more seen)
                            (cons x (step more (conj seen k))))))))]
    (step coll #{})))

(defn string->num [s]
  (try
    (let [num (bigdec s)]
      (if (and (> num Integer/MIN_VALUE) (< num Integer/MAX_VALUE)) (int num) num))
  (catch Exception e nil)))

(def safe-split (fnil split ""))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn make-label
  "Looks into an ability for :label, if it doesn't find it, capitalizes :msg instead."
  [ability]
  (or (:label ability) (and (string? (:msg ability)) (capitalize (:msg ability))) ""))

(defn click-spent?
  "Returns true if player has spent at least one click"
  [side state]
  (case side
    :challenger (contains? (into {} (get @state :turn-events)) :challenger-spent-click)
    :contestant   (contains? (into {} (get @state :turn-events)) :contestant-spent-click)))

(defn used-this-turn?
  "Returns true if a card has been used this turn"
  [cid state]
  (contains? (get-in @state [:per-turn]) cid))

(defn cancellable
  "Wraps a vector of prompt choices with a final 'Cancel' option. Optionally sorts the vector alphabetically,
  with Cancel always last."
  ([choices] (cancellable choices false))
  ([choices sorted]
   (if sorted
     (conj (vec (sort-by :title choices)) "Cancel")
     (conj (vec choices) "Cancel"))))

(defn cost-names
  "Converts a cost (value attribute pair) to a string for printing"
  [value attr]
  (when (and (number? value)
             (pos? value))
    (case attr
      :credit (str value " [$]")
      :click (->> "[Click]" repeat (take value) (apply str))
      :forfeit (str value " Agenda" (when (> value 1) "s"))
      nil)))

(defn build-cost-str
  "Gets the complete cost-str for specified costs"
  [costs]
  (->> costs
       (map #(cost-names (second %) (first %)))
       (filter some?)
       (interpose " and ")
       (apply str)))

(defn build-spend-msg
  "Constructs the spend message for specified cost-str and verb(s)."
  ([cost-str verb] (build-spend-msg cost-str verb nil))
  ([cost-str verb verb2]
   (if (or (not (instance? String cost-str))
           (= "" cost-str))
     (str (or verb2 (str verb "s")) " ")
     (str "spends " cost-str " to " verb " "))))

(defn other-side [side]
  (cond (= side :contestant) :challenger
        (= side :challenger) :contestant))

(defn side-str
  "Converts kw into str. If str is passed same str is returned."
  [side]
  (cond
    (= side :contestant) "Contestant"
    (= side "Contestant") "Contestant"
    (= side :challenger) "Challenger"
    (= side "Challenger") "Challenger"))

(defn same-side?
  "Checks if two supplied sides are the same side. Accepts both keyword and str."
  [side1 side2]
  (= (side-str side1) (side-str side2)))

(defn same-card?
  "Checks if the two cards are the same by :cid. Alternatively specify 1-function to use to check the card"
  ([card1 card2] (same-card? :cid card1 card2))
  ([func card1 card2]
    (= (func card1) (func card2))))

;;; Functions for working with zones.
(defn party-num->name [num]
  (str "Locale " num))

(defn party->name
  "Converts a party zone to a string"
  [zone]
  (let [kw (if (keyword? zone) zone (last zone))
        s (str kw)]
    (when (.startsWith s ":party")
      (let [num (last (split s #":party"))]
        (party-num->name num)))))

(defn central->name
  "Converts a central zone keyword to a string."
  [zone]
  (case (if (keyword? zone) zone (last zone))
    :hq "HQ"
    :rd "R&D"
    :archives "Archives"
    :sites "Sites"
    nil))

(defn zone->name
  "Converts a zone to a string."
  [zone]
  (or (central->name zone)
      (party->name zone)))

(defn zone->sort-key [zone]
  (case (if (keyword? zone) zone (last zone))
    :sites -4
    :archives -3
    :rd -2
    :hq -1
    (string->num
      (last (safe-split (str zone) #":party")))))

(defn zones->sorted-names [zones]
  (->> zones (sort-by zone->sort-key) (map zone->name)))

(defn is-party?
  "Returns true if the zone is for a party locale"
  [zone]
  (not (nil? (party->name zone))))

(defn is-central?
  "Returns true if the zone is for a central locale"
  [zone]
  (not (is-party? zone)))

(defn central->zone
  "Converts a central locale keyword like :discard into a corresponding zone vector"
  [zone]
  (case (if (keyword? zone) zone (last zone))
    :location [:locales :sites]
    :discard [:locales :archives]
    :hand [:locales :hq]
    :deck [:locales :rd]
    nil))

(defn type->rig-zone
  "Converts a challenger's card type to a vector zone, e.g. 'Resource' -> [:rig :resource]"
  [type]
  (vec [:rig (-> type .toLowerCase keyword)]))

(defn get-locale-type [zone]
  (or (#{:hq :rd :archives :sites} zone) :party))

(defn get-cid
  "Gets the cid of a given card"
  [card]
  (get-in card [:card :cid]))

(defn private-card [card]
  (select-keys card [:zone :cid :side :new :host :counter :advance-counter :hosted :icon]))

(defn combine-subtypes
  "Takes an existing subtype-string, adds in the new-subtypes, and removes
  duplicates if is-distinct is truthy"
  [is-distinct subtype-string & new-subtypes]
  (let [do-distinct #(if is-distinct (distinct %) %)]
    (->> (split (or subtype-string " - ") #" - ")
         (concat new-subtypes)
         do-distinct
         (join " - "))))

(defn remove-subtypes
  "Takes an existing subtype-string and removes all instances of
  subtypes-to-remove"
  [subtype-string & subtypes-to-remove]
  (->> (split (or subtype-string " - ") #" - ")
       (remove #(some #{%} subtypes-to-remove))
       (join " - ")))

(defn remove-subtypes-once
  "Takes an existing subtype-string and removes one instance of
  each subtypes-to-remove"
  [subtype-string subtypes-to-remove]
  (let [types (split (or subtype-string " - ") #" - ")
        part (join " - " (remove-once #(= % (first subtypes-to-remove)) types))
        left (rest subtypes-to-remove)]
    (if-not (empty? left)
      (remove-subtypes-once part left)
      part)))

(defn pluralize
  "Makes a string plural based on the number n. Takes specific suffixes for singular and plural cases if necessary."
  ([string n] (pluralize string "s" n))
  ([string suffix n] (pluralize string "" suffix n))
  ([string single-suffix plural-suffix n]
   (if (or (= 1 n)
           (= -1 n))
     (str string single-suffix)
     (str string plural-suffix))))

(defn quantify
  "Ensures the string is correctly pluralized based on the number n."
  ([n string] (str n " " (pluralize string n)))
  ([n string suffix] (str n " " (pluralize string suffix n)))
  ([n string single-suffix plural-suffix]
   (str n " " (pluralize string single-suffix plural-suffix n))))

(defn get-counters
  "Get number of counters of specified type."
  [card counter]
  (cond
    (= counter :advancement)
    (:advance-counter card 0)
    (= counter :recurring)
    (:rec-counter card 0)
    :else
    (get-in card [:counter counter] 0)))
