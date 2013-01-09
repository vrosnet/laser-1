(ns me.raynes.laser
  (:refer-clojure :exclude [remove replace and or])
  (:require [clojure.core :as clj]
            [hickory.core :as hickory]
            [hickory.zip :refer [hickory-zip]]
            [clojure.zip :as zip]
            [me.raynes.laser.zip :as lzip]
            [clojure.string :as string]))

(defn zipper?
  "Checks to see if the object has zip/make-node metadata on it (confirming it
   to be a zipper."
  [obj]
  (contains? (meta obj) :zip/make-node))

(defn zip
  "Get a zipper suitable for passing to fragment, document, or select, from
   a hickory node or a sequence of hickory nodes."
  [n]
  (cond
   (zipper? n) n
   (sequential? n) (map zip n)
   :else (hickory-zip n)))

(defn parse
  "Parses an HTML document. This is for top-level full documents,
   complete with <body>, <head>, and <html> tags. If they are not
   present, they will be added to the final result. s can be a string
   in which case it will be treated as a string of HTML or it can be
   something that can be slurped (reader, file, etc)."
  [s]
  (-> (if (string? s)
        s
        (slurp s))
      (hickory/parse)
      (hickory/as-hickory)
      (zip)))

(defn parse-fragment*
  "Like parse-fragment, but don't get a zipper over it."
  [s]
  (map hickory/as-hickory
       (hickory/parse-fragment
        (if (string? s)
          s
          (slurp s)))))

(defn parse-fragment
  "Parses an HTML fragment. s can be a string in which case it will be treated
   as a string of HTML or it can be something than can be slurped (reader, file,
   etc)."
  [s]
  (zip (parse-fragment* s)))

(defn to-html
  "Convert a hickory zip back to html."
  [z]
  (-> (if (sequential? z)
        (zip/root z)
        z)
      hickory/hickory-to-html))

(defn fragment-to-html
  "Takes a parsed fragment and converts it back to HTML."
  [z]
  (string/join (map to-html z)))

(defn ^:private merge? [loc]
  (:merge-left (meta loc)))

(defn ^:private merge-left [locs]
  (with-meta locs {:merge-left true}))

(defn ^:private edit [l f & args]
  (let [result (apply f (zip/node l) args)]
    (if (sequential? result)
      (merge-left (for [node result] (clj/or node "")))
      (zip/replace l (clj/or result "")))))

(defn ^:private apply-selector
  "If the selector matches, run transformation on the loc."
  [loc [selector transform]]
  (if (clj/and (selector loc) (map? (zip/node loc)))
    (let [edited (edit loc transform)]
      (if (merge? edited)
        edited
        [edited]))
    [loc]))

(defn ^:private apply-selectors [loc selectors]
  (let [result (reduce (fn [locs selector]
                         (mapcat #(apply-selector (zip %) selector) locs))
                       [loc]
                       selectors)]
    (if (> (count result) 1)
      (if (zip/up loc)
        (lzip/remove (reduce #(zip/insert-left % %2) loc result))
        (merge-left result))
      (zip (first result)))))

(defn ^:private traverse-zip
  "Iterate through an HTML zipper, running selectors and relevant transformations
   on each node."
  [selectors zip]
  (loop [loc zip]
    (cond
     (merge? loc) loc
     (zip/end? loc) (zip/root loc)
     :else (let [new-loc (apply-selectors loc selectors)]
             (recur (if (merge? new-loc)
                      new-loc
                      (lzip/next new-loc)))))))

(defn nodes
  "Normalizes nodes. If s is a string, parse it as a fragment and get
   a sequence of nodes. If s is sequential already, return it assuming
   it is already a seq of nodes. If it is anything else, wrap it in a
   vector (for example, if it is a map, this will make it a vector of
   maps (nodes)"
  [s]
  (cond
   (string? s) (parse-fragment* s)
   (sequential? s) s
   :else [s]))

(defn node
  "Create a hickory node. The most information you need to provide is the tag
   name. Optional keyword arguments allow you to provide the rest. If you don't,
   defaults will be provided. Keys that can be passed are :type, :content, and
   :attrs"
  [tag & {:keys [type content attrs]
          :or {type :element}}]
  {:tag tag
   :type type
   :content (if (clj/or (sequential? content) (nil? content))
              content
              [content])
   :attrs attrs})

;; Selectors

(defn element=
  "A selector that matches an element with this name."
  [element]
  (fn [loc] (= element (-> loc zip/node :tag))))

(defn attr=
  "A selector that checks to see if attr exists and has the value."
  [attr value]
  (fn [loc]
    (= value (get-in (zip/node loc) [:attrs attr]))))

(defn re-attr
  "A selector that checks to see if attr exists and the regex matches
   its value."
  [attr re]
  (fn [loc]
    (re-find re (get-in (zip/node loc) [:attrs attr] ""))))

(defn attr?
  "A selector that matches any element that has the attribute,
   regardless of value."
  [attr]
  (fn [loc]
    (-> (zip/node loc)
        (:attrs)
        (contains? attr))))

(defn ^:private split-classes [loc]
  (set (string/split (get-in (zip/node loc) [:attrs :class] "") #" ")))

(defn class=
  "A selector that matches if the node has these classes."
  [& classes]
  (fn [loc] (every? (split-classes loc) classes)))

(defn re-class
  "A selector that matches if any of the node's classes match a regex."
  [re]
  (fn [loc]
    (some (partial re-find re) (split-classes loc))))

(defn id=
  "A selector that matches the node's id."
  [id] (attr= :id id))

(defn any
  "A selector that matches any node."
  [] (constantly true))

;; Selector combinators

(defn negate
  "Negates a selector. Like clojure.core/not."
  [selector]
  (fn [loc] (not (selector loc))))

(defn and
  "Like and, but for selectors. Returns true iff all selectors match."
  [& selectors]
  (fn [loc] (every? identity (map #(% loc) selectors))))

(defn or
  "Like or, but for selectors. Returns true iff at least one selector matches.
   Like 'foo,bar' in css."
  [& selectors]
  (fn [loc] (boolean (some identity (map #(% loc) selectors)))))

(defn select-walk
  "A generalied function for implementing selectors that do the following
   1) check if the last selector matches the current loc, 2) check that the
   selector before it matches a new loc after a movement, and so on. Unless all
   the selectors match like this, the result is a non-match. The first argument
   is a function that will be run on the result of the selector call and the loc
   itself and should return true to continue or false to stop. The second argument
   tells the function how to move in the selector. For example, zip/up."
  [continue? move selectors]
  (fn [loc]
    (let [selectors (reverse selectors)
          selector (first selectors)]
      (if (selector loc)
        (loop [result false
               loc (move loc)
               [selector & selectors :as same] (rest selectors)]
          (cond
           (clj/and selector (nil? loc)) false
           (nil? selector) result
           :else (let [result (selector loc)]
                   (if (continue? result loc)
                     (recur result
                            (move loc)
                            (if result
                              selectors
                              same))
                     result))))
        false))))

(defn descendant-of
  "Checks that the last selector passed matches the current loc. If so,
   walks up the tree checking the next selector to see if it matches,
   any ancestor nodes. If so, repeat. Equivalent to 'foo bar baz' in CSS
   for matching a baz element that has a bar ancestor that has a foo
   ancestor."
  [& selectors]
  (select-walk (constantly true) zip/up selectors))

(defn adjacent-to
  "Checks that the last selector matches the current loc. If so,
   checks to see if the proceeding node matches the next selector.
   If so, repeat until all selectors are matched or one doesn't.
   Equivalent to 'foo + bar + baz' in CSS for matching a baz element
   is proceeded by a bar element that is proceeded by a foo element."
  [& selectors]
  (select-walk (fn [result _] result) zip/left selectors))

(defn child-of
  "Checks that the last selector matches the current loc. If so,
   checks to see if the immediate parent matches the next selector.
   If so, repeat. Equivalent to 'foo > bar > baz' in CSS for matching
   a baz element whose parent is a bar element whose parent is a foo
   element."
  [& selectors]
  (select-walk (fn [result _] result) zip/up selectors))

;; Transformers

(defn content
  "Set content of node to the string s. It will be escaped automatically
   by hickory when converting back to html."
  [s]
  (fn [node] (assoc node :content [s])))

(defn html-content
  "Set content of node to s, unescaped. Can take a string of HTML or
   already parsed nodes."
  [s]
  (fn [node] (assoc node :content (nodes s))))

(defn attr
  "Set attribute attr to value."
  [attr value]
  (fn [node] (assoc-in node [:attrs attr] value)))

(defn update-attr
  "Update an attribute with a function and optionally some args."
  [attr f & args]
  (fn [node] (apply update-in node [:attrs attr] f args)))

(defn classes
  "Set the node's class attribute to the string."
  [value]
  (attr :class value))

(defn id
  "Set the node's id to the string."
  [value]
  (attr :id value))

(defn add-class
  "Add a class to the node. Does not replace existng classes."
  [class]
  (fn [node]
    (update-in node [:attrs :class]
               #(str % (when (seq %) " ") class))))

(defn remove-class
  "Remove a class from a node. Does not touch other classes."
  [class]
  (fn [node]
    (update-in node [:attrs :class]
               #(string/join " " (clj/remove #{class} (string/split % #" "))))))

(defn wrap
  "Wrap a node around the node. Provide the element name as a key (like :div)
   and optionally a map of attributes."
  [tag & [attrs]]
  (fn [node] {:type :element :tag tag :attrs attrs :content [node]}))

(defn remove
  "Delete a node."
  [] (constantly nil))

(defn replace
  "Replace a node."
  [node] (constantly node))

;; High level

(defn document
  "Transform an HTML document. Use this for any top-level transformation.
   It expects a full HTML document (complete with <html> and <head>) and
   makes it one if it doesn't get one. Takes HTML parsed by the parse-html
   function."
  [s & fns]
  (to-html (traverse-zip (partition 2 fns) (lzip/leftmost-descendant s))))

(defn fragment
  "Transform an HTML fragment. Use document for transforming full HTML
   documents. This function does not return HTML, but instead returns a
   sequence of zippers of the transformed HTML. This is to make
   composing fragments faster. You can call to-html on the output to get
   HTML."
  [s & fns]
  (let [pairs (partition 2 fns)]
    (reduce #(if (sequential? %2)
               (into % %2)
               (conj % %2))
            []
            (for [node s]
              (traverse-zip pairs (lzip/leftmost-descendant node))))))

(defn at
  "Takes a single hickory node (like a transformer function) and walks it
   applying selectors and transformers just like fragment. Useful for
   doing sub-walks inside of fragments and document transformers. Has nothing
   to do with Enlive."
  [node & fns]
  (first (apply fragment (zip [node]) fns)))

(defmacro defragment
  "Define a function that transforms a fragment of HTML. The first
   argument should be the name of the function, the second argument
   is the string of HTML or readable thing (such as a resource from
   clojure.java.io/resource or a file), third argument are arguments
   the function can take, and an optional forth argument should be a
   vector of bindings to give to let that will be visible to the body.
   The rest of the arguments are selector and transformer pairs."
  [name s args bindings & transformations]
  `(let [html# (parse-fragment ~s)]
     (defn ~name ~args
       (let ~(if (vector? bindings) bindings [])
         (fragment html# ~@(if (vector? bindings)
                             transformations
                             (cons bindings transformations)))))))

(defmacro defdocument
  "Define a function that transforms an HTML document. The first
   argument should be the name of the function, the second argument
   is the string of HTML or readable thing (such as a resource from
   clojure.java.io/resource or a file), third argument are arguments
   the function can take, and an optional forth argument should be a
   vector of bindings to give to let that will be visible to the body.
   The rest of the arguments are selector and transformer pairs."
  [name s args bindings & transformations]
  `(let [html# (parse ~s)]
     (defn ~name ~args
       (let ~(if (vector? bindings) bindings [])
         (document html# ~@(if (vector? bindings)
                             transformations
                             (cons bindings transformations)))))))

;; Screen scraping

(defn text
  "Returns the text value of a node and its contents."
  [node] 
  (cond
   (string? node) node
   (map? node) (string/join (map text (:content node)))
   :else ""))

(defn ^:private zip-seq
  "Get a seq of all of the nodes in a zipper."
  [zip]
  (take-while (comp not zip/end?) (iterate zip/next zip)))

(defn select-locs
  "Select locs that match one of the selectors."
  [zip & selectors]
  (for [loc (zip-seq zip)
        :when ((apply some-fn selectors) loc)]
    loc))

(defn select
  "Select nodes that match one of the selectors."
  [zip & selectors]
  (map zip/node (apply select-locs zip selectors)))