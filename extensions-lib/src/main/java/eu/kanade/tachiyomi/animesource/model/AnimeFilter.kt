package eu.kanade.tachiyomi.animesource.model

sealed class AnimeFilter<T>(val name: String, var state: T) {
    /**
     * A simple header. Useful for separating sections in the list or showing any note or warning to the user.
     */
    open class Header(name: String) : AnimeFilter<Any>(name, 0)

    /**
     * A line separator. Useful for visual distinction between sections.
     */
    open class Separator(name: String = "") : AnimeFilter<Any>(name, 0)

    /**
     * A select control, similar to HTML's `<select>`. Only one item can be selected.
     *
     * @param name The filter text.
     * @param values The options list.
     * @param state The index of the selected item.
     */
    abstract class Select<V>(name: String, val values: Array<V>, state: Int = 0) : AnimeFilter<Int>(name, state)

    /**
     * A text control, similar to HTML's `<input type="text">`.
     *
     * @param name The placeholder text.
     * @param state The text written on it.
     */
    abstract class Text(name: String, state: String = "") : AnimeFilter<String>(name, state)

    /**
     * A checkbox control, similar to HTML's `<input type="checkbox">`.
     *
     * @param name The checkbox text
     * @param state A boolean that will be `true` if it's checked.
     */
    abstract class CheckBox(name: String, state: Boolean = false) : AnimeFilter<Boolean>(name, state)

    /**
     * A enhanced checkbox control that supports an excluding state.
     * The state can be compared with `STATE_IGNORE`, `STATE_INCLUDE` and `STATE_EXCLUDE` constants of the class.
     */
    abstract class TriState(name: String, state: Int = STATE_IGNORE) : AnimeFilter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE
        fun isIncluded() = state == STATE_INCLUDE
        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    /**
     * A group of filters.
     * Usually used for multiple related [CheckBox]/[TriState] instances, like in a genres filter
     *
     * @param name The filter group name
     * @param state a `List` with all the states.
     */
    abstract class Group<V>(name: String, state: List<V>) : AnimeFilter<List<V>>(name, state)

    /**
     * A control for sorting, with support for the ordering.
     * The state indicates which item index is selected and if the sorting is ascending.
     */
    abstract class Sort(name: String, val values: Array<String>, state: Selection? = null) : AnimeFilter<Sort.Selection?>(name, state) {
        data class Selection(val index: Int, val ascending: Boolean)
    }
}
