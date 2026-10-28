//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The core module runs on the app's minSdk, but as a plain JVM module it is not checked by Android lint: every Java
 * library method its compiled classes call must exist at that API level (the SDK's api-versions.xml, found through
 * ANDROID_HOME, which the Docker build image sets). A method reached through a superclass or interface counts at the
 * level that type declares it. Fails on, e.g., InputStream.readNBytes(int), API level 33, with minSdk 30.
 */
class AndroidApiLevelTest {
    private class Api(
        val since: Int,
        val members: Map<String, Int>,
        val parents: List<String>,
    )

    @Test
    fun coreCallsOnlyMethodsOfTheAppsMinSdk() {
        val home = System.getenv("ANDROID_HOME")
        assumeTrue("ANDROID_HOME not set: the Android API levels are not available", !home.isNullOrEmpty())
        val database = File(home!!, "platforms").listFiles()?.map { File(it, "data/api-versions.xml") }
            ?.filter { it.isFile }?.maxByOrNull { it.path }
        assertTrue("no api-versions.xml under $home/platforms", database != null)
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(File("../app/build.gradle.kts").readText())!!
            .groupValues[1].toInt()
        val api = load(database!!)
        val classes = File("build/classes/kotlin/main")
        assertTrue("no compiled main classes in ${classes.absolutePath}", classes.isDirectory)
        val newer = sortedSetOf<String>()
        val unknown = sortedSetOf<String>()
        classes.walk().filter { it.extension == "class" }.forEach { file ->
            for ((owner, member) in methodRefs(file)) {
                if (!owner.startsWith("java/") || owner.startsWith("java/lang/invoke/")) continue
                when (val level = level(api, owner, member)) {
                    null -> unknown += "$owner.$member"
                    else -> if (level > minSdk) newer += "$owner.$member (API $level)"
                }
            }
        }
        assertTrue("Java APIs newer than minSdk $minSdk:\n" + newer.joinToString("\n"), newer.isEmpty())
        assertTrue("Java APIs not in the Android SDK:\n" + unknown.joinToString("\n"), unknown.isEmpty())
    }

    /** The API level of a method of a class or of one of its supertypes, or null when none declares it. */
    private fun level(api: Map<String, Api>, owner: String, member: String, seen: MutableSet<String> = mutableSetOf()): Int? {
        if (!seen.add(owner)) return null
        val type = api[owner] ?: return null
        type.members[member]?.let { return it }
        return type.parents.firstNotNullOfOrNull { level(api, it, member, seen) }
    }

    /** api-versions.xml: per class its level, its methods' levels (the class's when not given) and its supertypes. */
    private fun load(file: File): Map<String, Api> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        fun since(element: Element, fallback: Int) =
            element.getAttribute("since").takeIf { it.isNotEmpty() }?.substringBefore('.')?.toInt() ?: fallback
        val result = HashMap<String, Api>()
        val classes = document.getElementsByTagName("class")
        for (i in 0 until classes.length) {
            val element = classes.item(i) as Element
            val level = since(element, 1)
            val members = HashMap<String, Int>()
            val parents = mutableListOf<String>()
            val children = element.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                when (child.tagName) {
                    "method" -> members[child.getAttribute("name")] = since(child, level)
                    "extends", "implements" -> parents += child.getAttribute("name")
                }
            }
            result[element.getAttribute("name")] = Api(level, members, parents)
        }
        return result
    }

    /** The (class, name + descriptor) of every Methodref and InterfaceMethodref of a class file's constant pool. */
    private fun methodRefs(file: File): List<Pair<String, String>> {
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.readInt()
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = HashMap<Int, String>()
            val classes = HashMap<Int, Int>()
            val nameTypes = HashMap<Int, Pair<Int, Int>>()
            val refs = mutableListOf<Pair<Int, Int>>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ }
                    7 -> classes[index] = input.readUnsignedShort()
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                    10, 11 -> refs += input.readUnsignedShort() to input.readUnsignedShort()
                    12 -> nameTypes[index] = input.readUnsignedShort() to input.readUnsignedShort()
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    else -> error("constant pool tag $tag in ${file.name}")
                }
                index++
            }
            return refs.mapNotNull { (owner, nameType) ->
                val name = utf8[classes[owner] ?: return@mapNotNull null] ?: return@mapNotNull null
                val (memberName, descriptor) = nameTypes[nameType] ?: return@mapNotNull null
                if (name.startsWith("[")) null else name to (utf8[memberName] + utf8[descriptor])
            }
        }
    }
}
