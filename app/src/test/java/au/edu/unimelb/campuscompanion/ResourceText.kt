package au.edu.unimelb.campuscompanion

import au.edu.unimelb.campuscompanion.ui.text.UiText
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Reads the real default resources so JVM tests exercise translated templates, not copies. */
object ResourceText {
    private val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml")).documentElement
    val strings: Map<String, String> = root.getElementsByTagName("string").let { nodes ->
        (0 until nodes.length).associate { index ->
            val node = nodes.item(index) as Element
            node.getAttribute("name") to decode(node.textContent)
        }
    }
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
    fun resolveQuantity(id: Int, count: Int, args: Array<out Any>): String = quantity(pluralNames.getValue(id), count, *args)

    fun resolve(id: Int, args: Array<out Any>): String = format(strings.getValue(names.getValue(id)), *args)

    fun quantity(name: String, count: Int, vararg args: Any): String {
        val nodes = root.getElementsByTagName("plurals")
        val plural = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == name }
        val items = plural.getElementsByTagName("item")
        val key = if (count == 1) "one" else "other"
        val value = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("quantity") == key }.textContent
        return format(decode(value), *args)
    }

    private fun decode(value: String): String = value.removeSurrounding("\"")
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

    private fun format(value: String, vararg args: Any): String =
        if (args.isEmpty()) value else String.format(Locale.ENGLISH, value, *args)
}

fun UiText.english(): String = resolve(ResourceText::resolve, ResourceText::resolveQuantity)
