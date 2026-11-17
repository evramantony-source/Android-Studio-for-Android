package com.example.compiler

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-Kotlin Android Binary XML (AXML) encoder for AndroidManifest.xml and simple XML resources.
 * Produces AOSP-compliant ResXMLTree chunks consumable by Android's PackageParser / ParsingPackageUtils.
 */
object BinaryXmlEncoder {

    private const val RES_STRING_POOL_TYPE: Short = 0x0001
    private const val RES_XML_TYPE: Short = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE: Short = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE: Short = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE: Short = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE: Short = 0x0103
    private const val RES_XML_RESOURCE_MAP_TYPE: Short = 0x0180

    private const val UTF8_FLAG = 0x00000100

    private const val TYPE_REFERENCE: Byte = 0x01
    private const val TYPE_STRING: Byte = 0x03
    private const val TYPE_FLOAT: Byte = 0x04
    private const val TYPE_INT_DEC: Byte = 0x10
    private const val TYPE_INT_HEX: Byte = 0x11
    private const val TYPE_INT_BOOLEAN: Byte = 0x12
    private const val TYPE_INT_COLOR_ARGB8: Byte = 0x1c

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    // Verified android.R.attr resource IDs for AndroidManifest.xml elements
    internal val ANDROID_ATTR_IDS: Map<String, Int> = mapOf(
        "theme" to 0x01010000,
        "label" to 0x01010001,
        "icon" to 0x01010002,
        "name" to 0x01010003,
        "manageSpaceActivity" to 0x01010004,
        "allowClearUserData" to 0x01010005,
        "permission" to 0x01010006,
        "readPermission" to 0x01010007,
        "writePermission" to 0x01010008,
        "protectionLevel" to 0x01010009,
        "permissionGroup" to 0x0101000a,
        "sharedUserId" to 0x0101000b,
        "hasCode" to 0x0101000c,
        "persistent" to 0x0101000d,
        "enabled" to 0x0101000e,
        "debuggable" to 0x0101000f,
        "exported" to 0x01010010,
        "process" to 0x01010011,
        "taskAffinity" to 0x01010012,
        "multiprocess" to 0x01010013,
        "finishOnTaskLaunch" to 0x01010014,
        "clearTaskOnLaunch" to 0x01010015,
        "stateNotNeeded" to 0x01010016,
        "excludeFromRecents" to 0x01010017,
        "authorities" to 0x01010018,
        "syncable" to 0x01010019,
        "initOrder" to 0x0101001a,
        "grantUriPermissions" to 0x0101001b,
        "priority" to 0x0101001c,
        "launchMode" to 0x0101001d,
        "screenOrientation" to 0x0101001e,
        "configChanges" to 0x0101001f,
        "description" to 0x01010020,
        "targetPackage" to 0x01010021,
        "handleProfiling" to 0x01010022,
        "functionalTest" to 0x01010023,
        "value" to 0x01010024,
        "resource" to 0x01010025,
        "mimeType" to 0x01010026,
        "scheme" to 0x01010027,
        "host" to 0x01010028,
        "port" to 0x01010029,
        "path" to 0x0101002a,
        "pathPrefix" to 0x0101002b,
        "pathPattern" to 0x0101002c,
        "targetActivity" to 0x01010202,
        "alwaysRetainTaskState" to 0x01010203,
        "allowTaskReparenting" to 0x01010204,
        "minSdkVersion" to 0x0101020c,
        "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c,
        "windowSoftInputMode" to 0x0101022b,
        "noHistory" to 0x0101022d,
        "sharedUserLabel" to 0x01010261,
        "targetSdkVersion" to 0x01010270,
        "maxSdkVersion" to 0x01010271,
        "testOnly" to 0x01010272,
        "backupAgent" to 0x0101027f,
        "allowBackup" to 0x01010280,
        "glEsVersion" to 0x01010281,
        "required" to 0x0101028e,
        "installLocation" to 0x010102b7,
        "vmSafeMode" to 0x010102b8,
        "restoreAnyVersion" to 0x010102ba,
        "logo" to 0x010102be,
        "hardwareAccelerated" to 0x010102d3,
        "largeHeap" to 0x0101035a,
        "stopWithTask" to 0x0101036a,
        "uiOptions" to 0x01010398,
        "parentActivityName" to 0x010103a7,
        "isolatedProcess" to 0x010103a9,
        "supportsRtl" to 0x010103af,
        "singleUser" to 0x010103bf,
        "permissionFlags" to 0x010103c7,
        "category" to 0x010103e8,
        "banner" to 0x010103f2,
        "isGame" to 0x010103f4,
        "allowEmbedded" to 0x010103f5,
        "documentLaunchMode" to 0x01010445,
        "maxRecents" to 0x01010446,
        "autoRemoveFromRecents" to 0x01010447,
        "fullBackupOnly" to 0x01010473,
        "relinquishTaskIdentity" to 0x01010476,
        "extractNativeLibs" to 0x010104ea,
        "fullBackupContent" to 0x010104eb,
        "usesCleartextTraffic" to 0x010104ec,
        "autoVerify" to 0x010104ee,
        "resizeableActivity" to 0x010104f6,
        "supportsPictureInPicture" to 0x010104f7,
        "networkSecurityConfig" to 0x01010527,
        "roundIcon" to 0x0101052c,
        "appCategory" to 0x01010545,
        "colorMode" to 0x0101054a,
        "isolatedSplits" to 0x0101054b,
        "targetSandboxVersion" to 0x0101054c,
        "requiredFeature" to 0x01010554,
        "requiredNotFeature" to 0x01010555,
        "compileSdkVersion" to 0x01010572,
        "compileSdkVersionCodename" to 0x01010573,
        "versionCodeMajor" to 0x01010576,
        "appComponentFactory" to 0x0101057a,
        "foregroundServiceType" to 0x01010599,
        "requestLegacyExternalStorage" to 0x01010603,
        "preserveLegacyExternalStorage" to 0x01010614,
        "pathSuffix" to 0x0101061e,
        "pathAdvancedPattern" to 0x01010620,
        "dataExtractionRules" to 0x0101063e,
        "attributionTags" to 0x01010642,
        "usesPermissionFlags" to 0x01010644,
        "localeConfig" to 0x0101065b,
        "enableOnBackInvokedCallback" to 0x0101066c,
        // Common Layout & View attributes for res/layout/*.xml
        "id" to 0x010100d0,
        "background" to 0x010100d4,
        "padding" to 0x010100d5,
        "paddingLeft" to 0x010100d6,
        "paddingTop" to 0x010100d7,
        "paddingRight" to 0x010100d8,
        "paddingBottom" to 0x010100d9,
        "visibility" to 0x010100dc,
        "gravity" to 0x010100af,
        "orientation" to 0x010100c4,
        "layout_width" to 0x010100f4,
        "layout_height" to 0x010100f5,
        "layout_weight" to 0x01010181,
        "layout_gravity" to 0x010100b3,
        "layout_margin" to 0x010100f6,
        "text" to 0x0101014f,
        "hint" to 0x01010150,
        "textSize" to 0x01010095,
        "textColor" to 0x01010098,
        "textStyle" to 0x01010097,
        "src" to 0x01010119,
        "inputType" to 0x01010220,
        "columnCount" to 0x01010377,
    )

    // Verified android.R.style resource IDs
    private val ANDROID_STYLE_IDS: Map<String, Int> = mapOf(
        "Theme.Material" to 0x01030224,
        "Theme.Material.NoActionBar" to 0x0103022e,
        "Theme.Material.NoActionBar.Fullscreen" to 0x0103022f,
        "Theme.Material.Light" to 0x01030237,
        "Theme.Material.Light.DarkActionBar" to 0x01030238,
        "Theme.Material.Light.NoActionBar" to 0x01030241,
        "Theme.Material.Light.NoActionBar.Fullscreen" to 0x01030242,
        "Theme.Material.Dialog" to 0x01030225,
        "Theme.Material.Light.Dialog" to 0x01030239,
        "Theme.DeviceDefault" to 0x01030128,
        "Theme.DeviceDefault.DayNight" to 0x010302e3,
        "Theme.DeviceDefault.NoActionBar" to 0x01030129,
        "Theme.DeviceDefault.NoActionBar.Fullscreen" to 0x0103012a,
        "Theme.DeviceDefault.Light" to 0x0103012b,
        "Theme.DeviceDefault.Light.DarkActionBar" to 0x0103013f,
        "Theme.DeviceDefault.Light.NoActionBar" to 0x0103012c,
        "Theme.DeviceDefault.Light.NoActionBar.Fullscreen" to 0x0103012d,
        "Theme.Holo" to 0x0103006b,
        "Theme.Holo.NoActionBar" to 0x0103006c,
        "Theme.Holo.Light" to 0x0103006e,
        "Theme.Holo.Light.DarkActionBar" to 0x01030119,
        "Theme.Holo.Light.NoActionBar" to 0x010300f0,
        "Theme.NoTitleBar" to 0x01030006,
        "Theme.NoTitleBar.Fullscreen" to 0x01030007,
        "Theme.Black" to 0x01030008,
        "Theme.Black.NoTitleBar" to 0x01030009,
        "Theme.Black.NoTitleBar.Fullscreen" to 0x0103000a,
        "Theme.Dialog" to 0x0103000b,
        "Theme.Light" to 0x0103000c,
        "Theme.Light.NoTitleBar" to 0x0103000d,
        "Theme.Translucent" to 0x0103000f,
        "Theme.Translucent.NoTitleBar" to 0x01030010,
        "Theme.Translucent.NoTitleBar.Fullscreen" to 0x01030011,
    )

    private val ANDROID_COLOR_IDS: Map<String, Int> = mapOf(
        "darker_gray" to 0x01060000,
        "white" to 0x0106000b,
        "black" to 0x0106000c,
        "transparent" to 0x0106000d,
        "background_dark" to 0x0106000e,
        "background_light" to 0x0106000f,
    )

    private val SCREEN_ORIENTATION_VALUES: Map<String, Int> = mapOf(
        "unspecified" to -1,
        "landscape" to 0,
        "portrait" to 1,
        "user" to 2,
        "behind" to 3,
        "sensor" to 4,
        "nosensor" to 5,
        "sensorLandscape" to 6,
        "sensorPortrait" to 7,
        "reverseLandscape" to 8,
        "reversePortrait" to 9,
        "fullSensor" to 10,
        "userLandscape" to 11,
        "userPortrait" to 12,
        "fullUser" to 13,
        "locked" to 14,
    )

    private val LAUNCH_MODE_VALUES: Map<String, Int> = mapOf(
        "standard" to 0,
        "singleTop" to 1,
        "singleTask" to 2,
        "singleInstance" to 3,
        "singleInstancePerTask" to 4,
    )

    private val INSTALL_LOCATION_VALUES: Map<String, Int> = mapOf(
        "auto" to 0,
        "internalOnly" to 1,
        "preferExternal" to 2,
    )

    private val DOCUMENT_LAUNCH_MODE_VALUES: Map<String, Int> = mapOf(
        "none" to 0,
        "intoExisting" to 1,
        "always" to 2,
        "never" to 3,
    )

    private val UI_OPTIONS_VALUES: Map<String, Int> = mapOf(
        "none" to 0,
        "splitActionBarWhenNarrow" to 1,
    )

    private val PROTECTION_LEVEL_FLAGS: Map<String, Int> = mapOf(
        "normal" to 0,
        "dangerous" to 1,
        "signature" to 2,
        "signatureOrSystem" to 3,
        "privileged" to 0x10,
        "system" to 0x10,
        "development" to 0x20,
        "appop" to 0x40,
        "pre23" to 0x80,
        "installer" to 0x100,
        "verifier" to 0x200,
        "preinstalled" to 0x400,
        "setup" to 0x800,
        "instant" to 0x1000,
        "runtime" to 0x2000,
        "knownSigner" to 0x80000,
    )

    private val CONFIG_CHANGES_FLAGS: Map<String, Int> = mapOf(
        "mcc" to 0x0001,
        "mnc" to 0x0002,
        "locale" to 0x0004,
        "touchscreen" to 0x0008,
        "keyboard" to 0x0010,
        "keyboardHidden" to 0x0020,
        "navigation" to 0x0040,
        "orientation" to 0x0080,
        "screenLayout" to 0x0100,
        "uiMode" to 0x0200,
        "screenSize" to 0x0400,
        "smallestScreenSize" to 0x0800,
        "density" to 0x1000,
        "layoutDirection" to 0x2000,
        "colorMode" to 0x4000,
        "fontScale" to 0x40000000,
    )

    private val SOFT_INPUT_FLAGS: Map<String, Int> = mapOf(
        "stateUnspecified" to 0x00,
        "stateUnchanged" to 0x01,
        "stateHidden" to 0x02,
        "stateAlwaysHidden" to 0x03,
        "stateVisible" to 0x04,
        "stateAlwaysVisible" to 0x05,
        "adjustUnspecified" to 0x00,
        "adjustResize" to 0x10,
        "adjustPan" to 0x20,
        "adjustNothing" to 0x30,
    )

    private val FOREGROUND_SERVICE_FLAGS: Map<String, Int> = mapOf(
        "manifest" to -1,
        "dataSync" to 0x00000001,
        "mediaPlayback" to 0x00000002,
        "phoneCall" to 0x00000004,
        "location" to 0x00000008,
        "connectedDevice" to 0x00000010,
        "mediaProjection" to 0x00000020,
        "camera" to 0x00000040,
        "microphone" to 0x00000080,
        "health" to 0x00000100,
        "remoteMessaging" to 0x00000200,
        "systemExempted" to 0x00000400,
        "shortService" to 0x00000800,
        "mediaProcessing" to 0x00002000,
        "specialUse" to 0x40000000,
    )

    private val USES_PERMISSION_FLAGS: Map<String, Int> = mapOf(
        "neverForLocation" to 0x00010000,
    )

    private val STRICTLY_STRING_ATTRS: Set<String> = setOf(
        "package",
        "versionName",
        "name",
        "permission",
        "readPermission",
        "writePermission",
        "permissionGroup",
        "sharedUserId",
        "authorities",
        "process",
        "taskAffinity",
        "parentActivityName",
        "targetActivity",
        "targetPackage",
        "manageSpaceActivity",
        "backupAgent",
        "appComponentFactory",
        "compileSdkVersionCodename",
        "scheme",
        "host",
        "port",
        "path",
        "pathPrefix",
        "pathPattern",
        "pathSuffix",
        "pathAdvancedPattern",
        "mimeType",
        "category",
        "attributionTags",
        "splitName",
        "requiredFeature",
        "requiredNotFeature",
    )

    private sealed interface XmlEvent {
        data class StartNs(val prefix: String, val uri: String, val line: Int) : XmlEvent
        data class EndNs(val prefix: String, val uri: String, val line: Int) : XmlEvent
        data class StartTag(
            val ns: String?,
            val name: String,
            val attrs: List<Attr>,
            val line: Int,
        ) : XmlEvent

        data class EndTag(val ns: String?, val name: String, val line: Int) : XmlEvent
    }

    private data class Attr(
        val ns: String?,
        val name: String,
        val value: String,
        val resId: Int,
        val dataType: Byte,
        val resolvedData: Int,
    )

    private data class StringPoolKey(
        val text: String,
        val resId: Int,
    )

    fun encodeManifest(
        xmlText: String,
        resourceIds: Map<String, Int>,
    ): ByteArray {
        val events = parseXmlEvents(xmlText, resourceIds)

        val resStrings = linkedMapOf<StringPoolKey, Int>()
        val nonResStrings = linkedSetOf<String>()

        for (event in events) {
            when (event) {
                is XmlEvent.StartNs -> {
                    nonResStrings.add(event.prefix)
                    nonResStrings.add(event.uri)
                }
                is XmlEvent.EndNs -> {
                    nonResStrings.add(event.prefix)
                    nonResStrings.add(event.uri)
                }
                is XmlEvent.StartTag -> {
                    event.ns?.takeIf { it.isNotEmpty() }?.let { nonResStrings.add(it) }
                    nonResStrings.add(event.name)
                    for (attr in event.attrs) {
                        attr.ns?.takeIf { it.isNotEmpty() }?.let { nonResStrings.add(it) }
                        if (attr.resId != 0) {
                            resStrings.putIfAbsent(StringPoolKey(attr.name, attr.resId), attr.resId)
                        } else {
                            nonResStrings.add(attr.name)
                        }
                        if (attr.dataType == TYPE_STRING) {
                            nonResStrings.add(attr.value)
                        }
                    }
                }
                is XmlEvent.EndTag -> {
                    event.ns?.takeIf { it.isNotEmpty() }?.let { nonResStrings.add(it) }
                    nonResStrings.add(event.name)
                }
            }
        }

        val stringList = mutableListOf<String>()
        val resourceMap = mutableListOf<Int>()
        val resStringIndices = mutableMapOf<StringPoolKey, Int>()
        for ((key, resId) in resStrings) {
            resStringIndices[key] = stringList.size
            stringList.add(key.text)
            resourceMap.add(resId)
        }

        val nonResStringIndices = mutableMapOf<String, Int>()
        for (s in nonResStrings) {
            nonResStringIndices[s] = stringList.size
            stringList.add(s)
        }

        val stringPoolChunk = encodeUtf8StringPool(stringList)
        val resourceMapChunk = encodeResourceMap(resourceMap)
        val bodyStream = ByteArrayOutputStream()

        for (event in events) {
            when (event) {
                is XmlEvent.StartNs -> {
                    bodyStream.write(
                        encodeNamespaceChunk(
                            type = RES_XML_START_NAMESPACE_TYPE,
                            line = event.line,
                            prefixIdx = nonResStringIndices.getValue(event.prefix),
                            uriIdx = nonResStringIndices.getValue(event.uri),
                        ),
                    )
                }
                is XmlEvent.EndNs -> {
                    bodyStream.write(
                        encodeNamespaceChunk(
                            type = RES_XML_END_NAMESPACE_TYPE,
                            line = event.line,
                            prefixIdx = nonResStringIndices.getValue(event.prefix),
                            uriIdx = nonResStringIndices.getValue(event.uri),
                        ),
                    )
                }
                is XmlEvent.StartTag -> {
                    bodyStream.write(
                        encodeStartElementChunk(
                            event = event,
                            resStringIndices = resStringIndices,
                            nonResStringIndices = nonResStringIndices,
                        ),
                    )
                }
                is XmlEvent.EndTag -> {
                    bodyStream.write(
                        encodeEndElementChunk(
                            line = event.line,
                            nsIdx = event.ns?.takeIf { it.isNotEmpty() }?.let { nonResStringIndices.getValue(it) } ?: -1,
                            nameIdx = nonResStringIndices.getValue(event.name),
                        ),
                    )
                }
            }
        }

        val bodyBytes = bodyStream.toByteArray()
        val totalSize = 8 + stringPoolChunk.size + resourceMapChunk.size + bodyBytes.size
        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE)
        out.putShort(8)
        out.putInt(totalSize)
        out.put(stringPoolChunk)
        out.put(resourceMapChunk)
        out.put(bodyBytes)
        return out.array()
    }

    private fun parseXmlEvents(
        xmlText: String,
        resourceIds: Map<String, Int>,
    ): List<XmlEvent> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText.trim())))
        val events = mutableListOf<XmlEvent>()
        var lineCounter = 1

        fun visitElement(el: Element) {
            val line = lineCounter++
            val declaredNs = mutableListOf<Pair<String, String>>()
            val attrs = mutableListOf<Attr>()

            val namedMap = el.attributes
            for (i in 0 until namedMap.length) {
                val attrNode = namedMap.item(i)
                val ns = attrNode.namespaceURI?.takeIf { it.isNotEmpty() }
                val nodeName = attrNode.nodeName
                val localName = attrNode.localName ?: nodeName.substringAfter(':')
                val value = attrNode.nodeValue ?: ""

                if (ns == XMLNS_NS || nodeName.startsWith("xmlns")) {
                    val prefix = if (nodeName.contains(':')) nodeName.substringAfter(':') else ""
                    declaredNs.add(prefix to value)
                    events.add(XmlEvent.StartNs(prefix, value, line))
                } else {
                    val resId = if (ns == ANDROID_NS) ANDROID_ATTR_IDS[localName] ?: 0 else 0
                    val (dataType, resolvedData) = resolveTypedValue(
                        attrName = localName,
                        ns = ns,
                        raw = value,
                        resourceIds = resourceIds,
                    )
                    attrs.add(Attr(ns, localName, value, resId, dataType, resolvedData))
                }
            }

            val sortedAttrs = attrs.sortedWith(
                compareBy<Attr>(
                    { if (it.resId == 0) 1 else 0 },
                    { it.resId },
                    { it.name },
                ),
            )
            events.add(
                XmlEvent.StartTag(
                    ns = el.namespaceURI?.takeIf { it.isNotEmpty() },
                    name = el.localName ?: el.tagName,
                    attrs = sortedAttrs,
                    line = line,
                ),
            )

            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    visitElement(child as Element)
                }
            }

            events.add(
                XmlEvent.EndTag(
                    ns = el.namespaceURI?.takeIf { it.isNotEmpty() },
                    name = el.localName ?: el.tagName,
                    line = lineCounter,
                ),
            )
            for ((prefix, uri) in declaredNs.asReversed()) {
                events.add(XmlEvent.EndNs(prefix, uri, lineCounter))
            }
        }

        visitElement(doc.documentElement)
        return events
    }

    internal fun resolveTypedValue(
        attrName: String,
        ns: String?,
        raw: String,
        resourceIds: Map<String, Int>,
    ): Pair<Byte, Int> {
        // Explicit resource references first (including @string/@color/@style/@drawable/@mipmap)
        if (raw.startsWith("@android:style/")) {
            val styleName = raw.removePrefix("@android:style/")
            ANDROID_STYLE_IDS[styleName]?.let { return TYPE_REFERENCE to it }
        }
        if (raw.startsWith("@android:color/")) {
            val colorName = raw.removePrefix("@android:color/")
            ANDROID_COLOR_IDS[colorName]?.let { return TYPE_REFERENCE to it }
        }
        if (raw.startsWith("@+")) {
            val key = raw.removePrefix("@+")
            resourceIds[key]?.let { return TYPE_REFERENCE to it }
        }
        if (raw.startsWith("@")) {
            val key = raw.removePrefix("@")
            resourceIds[key]?.let { return TYPE_REFERENCE to it }
        }

        // Inherently string-typed attributes must stay TYPE_STRING even if text looks numeric
        if (attrName in STRICTLY_STRING_ATTRS || (attrName == "label" && !raw.startsWith("@"))) {
            return TYPE_STRING to 0
        }

        if (raw == "true") return TYPE_INT_BOOLEAN to -1
        if (raw == "false") return TYPE_INT_BOOLEAN to 0

        if (ns == ANDROID_NS) {
            resolveManifestEnumOrFlags(attrName, raw)?.let { return it }
        }

        if (raw.startsWith("#")) {
            val hex = raw.removePrefix("#")
            val parsed = when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                8 -> hex.toLongOrNull(16)?.toInt()
                else -> null
            }
            if (parsed != null) return TYPE_INT_COLOR_ARGB8 to parsed
        }

        if (raw.startsWith("0x", ignoreCase = true)) {
            raw.substring(2).toLongOrNull(16)?.let { return TYPE_INT_HEX to it.toInt() }
        }

        raw.toIntOrNull()?.let { return TYPE_INT_DEC to it }

        if (attrName == "value" && raw.contains('.')) {
            raw.toFloatOrNull()?.let { return TYPE_FLOAT to java.lang.Float.floatToIntBits(it) }
        }

        return TYPE_STRING to 0
    }

    private fun resolveManifestEnumOrFlags(attrName: String, raw: String): Pair<Byte, Int>? {
        val exactEnumMap = when (attrName) {
            "screenOrientation" -> SCREEN_ORIENTATION_VALUES
            "launchMode" -> LAUNCH_MODE_VALUES
            "installLocation" -> INSTALL_LOCATION_VALUES
            "documentLaunchMode" -> DOCUMENT_LAUNCH_MODE_VALUES
            "uiOptions" -> UI_OPTIONS_VALUES
            else -> null
        }
        if (exactEnumMap != null) {
            exactEnumMap[raw.trim()]?.let { return TYPE_INT_DEC to it }
        }

        val flagMap = when (attrName) {
            "configChanges" -> CONFIG_CHANGES_FLAGS
            "windowSoftInputMode" -> SOFT_INPUT_FLAGS
            "protectionLevel" -> PROTECTION_LEVEL_FLAGS
            "foregroundServiceType" -> FOREGROUND_SERVICE_FLAGS
            "usesPermissionFlags" -> USES_PERMISSION_FLAGS
            else -> null
        }
        if (flagMap != null) {
            val parts = raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.all { flagMap.containsKey(it) }) {
                var mask = 0
                for (p in parts) {
                    mask = mask or flagMap.getValue(p)
                }
                return TYPE_INT_HEX to mask
            }
        }
        return null
    }

    internal fun encodeUtf8StringPool(strings: List<String>): ByteArray {
        val offsets = IntArray(strings.size)
        val data = ByteArrayOutputStream()
        for ((index, str) in strings.withIndex()) {
            offsets[index] = data.size()
            val utf8 = str.toByteArray(Charsets.UTF_8)
            writeUtf8Length(data, str.length)
            writeUtf8Length(data, utf8.size)
            data.write(utf8)
            data.write(0)
        }
        while (data.size() % 4 != 0) {
            data.write(0)
        }
        val strBytes = data.toByteArray()
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val totalSize = stringsStart + strBytes.size

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(totalSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(UTF8_FLAG)
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        for (offset in offsets) {
            buf.putInt(offset)
        }
        buf.put(strBytes)
        return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, len: Int) {
        if (len >= 0x80) {
            out.write(((len shr 8) and 0x7F) or 0x80)
        }
        out.write(len and 0xFF)
    }

    private fun encodeResourceMap(resIds: List<Int>): ByteArray {
        val size = 8 + resIds.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_RESOURCE_MAP_TYPE)
        buf.putShort(8)
        buf.putInt(size)
        for (id in resIds) {
            buf.putInt(id)
        }
        return buf.array()
    }

    private fun encodeNamespaceChunk(
        type: Short,
        line: Int,
        prefixIdx: Int,
        uriIdx: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(type)
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(line)
        buf.putInt(-1) // comment
        buf.putInt(prefixIdx)
        buf.putInt(uriIdx)
        return buf.array()
    }

    private fun encodeStartElementChunk(
        event: XmlEvent.StartTag,
        resStringIndices: Map<StringPoolKey, Int>,
        nonResStringIndices: Map<String, Int>,
    ): ByteArray {
        val size = 36 + event.attrs.size * 20
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_START_ELEMENT_TYPE)
        buf.putShort(16) // ResXMLTree_node headerSize
        buf.putInt(size)
        buf.putInt(event.line)
        buf.putInt(-1) // comment

        buf.putInt(event.ns?.let { nonResStringIndices.getValue(it) } ?: -1)
        buf.putInt(nonResStringIndices.getValue(event.name))
        buf.putShort(20) // attributeStart
        buf.putShort(20) // attributeSize
        buf.putShort(event.attrs.size.toShort())
        buf.putShort(0) // idIndex
        buf.putShort(0) // classIndex
        buf.putShort(0) // styleIndex

        for (attr in event.attrs) {
            val rawValueIdx = if (attr.dataType == TYPE_STRING) {
                nonResStringIndices.getValue(attr.value)
            } else {
                -1
            }
            val dataValue = if (attr.dataType == TYPE_STRING) rawValueIdx else attr.resolvedData
            val attrNameIdx = if (attr.resId != 0) {
                resStringIndices.getValue(StringPoolKey(attr.name, attr.resId))
            } else {
                nonResStringIndices.getValue(attr.name)
            }

            buf.putInt(attr.ns?.let { nonResStringIndices.getValue(it) } ?: -1)
            buf.putInt(attrNameIdx)
            buf.putInt(rawValueIdx)
            buf.putShort(8) // Res_value size
            buf.put(0) // res0
            buf.put(attr.dataType)
            buf.putInt(dataValue)
        }
        return buf.array()
    }

    private fun encodeEndElementChunk(line: Int, nsIdx: Int, nameIdx: Int): ByteArray {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_END_ELEMENT_TYPE)
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(line)
        buf.putInt(-1)
        buf.putInt(nsIdx)
        buf.putInt(nameIdx)
        return buf.array()
    }
}
