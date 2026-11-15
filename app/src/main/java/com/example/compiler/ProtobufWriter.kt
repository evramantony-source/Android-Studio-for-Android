package com.example.compiler

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure-Kotlin Protocol Buffer wire encoder for Android App Bundle (.aab) artifacts:
 * - BundleConfig.pb (android.bundle.BundleConfig)
 * - base/resources.pb (aapt.pb.ResourceTable)
 * - base/manifest/AndroidManifest.xml (aapt.pb.XmlNode)
 */
object ProtobufWriter {

    private class ProtoBuf {
        private val out = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, 0)
            writeRawVarint(value)
        }

        fun writeStringField(fieldNumber: Int, value: String) {
            if (value.isEmpty()) return
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeTag(fieldNumber, 2)
            writeRawVarint(bytes.size.toLong())
            out.write(bytes)
        }

        fun writeMessageField(fieldNumber: Int, block: ProtoBuf.() -> Unit) {
            val sub = ProtoBuf().apply(block).toByteArray()
            writeTag(fieldNumber, 2)
            writeRawVarint(sub.size.toLong())
            out.write(sub)
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            writeRawVarint(((fieldNumber shl 3) or wireType).toLong())
        }

        private fun writeRawVarint(v: Long) {
            var value = v
            while (true) {
                if ((value and 0x7FL.inv()) == 0L) {
                    out.write(value.toInt())
                    return
                }
                out.write(((value.toInt() and 0x7F) or 0x80))
                value = value ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    fun encodeBundleConfig(): ByteArray = ProtoBuf().apply {
        // BundleConfig.bundletool (field 1)
        writeMessageField(1) {
            writeStringField(2, "1.17.2")
        }
        // BundleConfig.optimizations (field 2) -> splits_config (field 1)
        writeMessageField(2) {
            writeMessageField(1) {
                for (dim in intArrayOf(1, 2, 3)) { // ABI, SCREEN_DENSITY, LANGUAGE
                    writeMessageField(1) {
                        writeVarintField(1, dim.toLong())
                    }
                }
            }
        }
    }.toByteArray()

    fun encodeResourceTable(
        packageName: String,
        strings: List<Pair<String, String>>,
        colors: List<Pair<String, Int>>,
    ): ByteArray = ProtoBuf().apply {
        // ResourceTable.package (field 2)
        writeMessageField(2) {
            // Package.package_id (field 1) -> PackageId.id (field 1)
            writeMessageField(1) {
                writeVarintField(1, 0x7F)
            }
            writeStringField(2, packageName)

            // Type 1: string
            writeMessageField(3) {
                writeMessageField(1) { writeVarintField(1, 1) }
                writeStringField(2, "string")
                strings.forEachIndexed { idx, (name, value) ->
                    writeMessageField(3) {
                        writeMessageField(1) { writeVarintField(1, idx.toLong()) }
                        writeStringField(2, name)
                        writeMessageField(5) {
                            writeMessageField(2) {
                                writeMessageField(4) {
                                    writeMessageField(2) {
                                        writeStringField(1, value)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Type 2: color
            if (colors.isNotEmpty()) {
                writeMessageField(3) {
                    writeMessageField(1) { writeVarintField(1, 2) }
                    writeStringField(2, "color")
                    colors.forEachIndexed { idx, (name, argb) ->
                        writeMessageField(3) {
                            writeMessageField(1) { writeVarintField(1, idx.toLong()) }
                            writeStringField(2, name)
                            writeMessageField(5) {
                                writeMessageField(2) {
                                    writeMessageField(4) {
                                        writeMessageField(5) {
                                            writeVarintField(5, argb.toLong() and 0xFFFFFFFFL)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toByteArray()

    fun encodeProtoXml(
        xmlText: String,
        androidAttrIds: Map<String, Int>,
        resourceIds: Map<String, Int>,
    ): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText.trim())))

        fun ProtoBuf.writeElementNode(el: Element) {
            // XmlNode.element (field 1)
            writeMessageField(1) {
                val attrs = el.attributes
                val normalAttrs = mutableListOf<Triple<String, String, String>>()
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i)
                    val ns = attr.namespaceURI.orEmpty()
                    val nodeName = attr.nodeName.orEmpty()
                    val localName = attr.localName ?: nodeName.substringAfter(':')
                    val value = attr.nodeValue.orEmpty()
                    if (ns == "http://www.w3.org/2000/xmlns/" || nodeName.startsWith("xmlns")) {
                        val prefix = if (nodeName.contains(':')) nodeName.substringAfter(':') else ""
                        writeMessageField(1) {
                            writeStringField(1, prefix)
                            writeStringField(2, value)
                        }
                    } else {
                        normalAttrs.add(Triple(ns, localName, value))
                    }
                }
                writeStringField(2, el.namespaceURI.orEmpty())
                writeStringField(3, el.localName ?: el.tagName)
                for ((ns, localName, value) in normalAttrs) {
                    writeMessageField(4) {
                        writeStringField(1, ns)
                        writeStringField(2, localName)
                        writeStringField(3, value)
                        val resId = if (ns == "http://schemas.android.com/apk/res/android") {
                            androidAttrIds[localName] ?: 0
                        } else {
                            0
                        }
                        if (resId != 0) {
                            writeVarintField(5, resId.toLong())
                        }
                        if (value.startsWith("@string/") || value.startsWith("@color/")) {
                            resourceIds[value.removePrefix("@")]?.let { refId ->
                                writeMessageField(6) {
                                    writeMessageField(2) {
                                        writeVarintField(1, refId.toLong())
                                    }
                                }
                            }
                        }
                    }
                }
                val children = el.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i)
                    if (child.nodeType == Node.ELEMENT_NODE) {
                        writeMessageField(5) {
                            writeElementNode(child as Element)
                        }
                    }
                }
            }
        }

        return ProtoBuf().apply {
            writeElementNode(doc.documentElement)
        }.toByteArray()
    }
}
