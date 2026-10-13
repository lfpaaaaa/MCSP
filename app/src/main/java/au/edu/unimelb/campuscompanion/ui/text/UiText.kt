package au.edu.unimelb.campuscompanion.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Resolves app-owned copy at the UI boundary, without keeping an Activity in a model. */
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val arguments: List<Any>) : UiText
    data class Literal(val value: String) : UiText
    data class Joined(val parts: List<UiText>, val separator: String = " ") : UiText

    fun resolve(resources: Resources): String = resolve(
        getString = { id, args -> resources.getString(id, *args) },
        getQuantityString = { id, count, args -> resources.getQuantityString(id, count, *args) }
    )

    fun resolve(
        getString: (Int, Array<out Any>) -> String,
        getQuantityString: (Int, Int, Array<out Any>) -> String
    ): String {
        fun resolveArgument(argument: Any): Any =
            if (argument is UiText) argument.resolve(getString, getQuantityString) else argument
        return when (this) {
            is Resource -> getString(id, arguments.map(::resolveArgument).toTypedArray())
            is Plural -> getQuantityString(id, quantity, arguments.map(::resolveArgument).toTypedArray())
            is Literal -> value
            is Joined -> parts.joinToString(separator) { it.resolve(getString, getQuantityString) }
        }
    }

    companion object {
        operator fun invoke(@StringRes id: Int, vararg arguments: Any): UiText = Resource(id, arguments.toList())
    }
}

@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(textResources())
