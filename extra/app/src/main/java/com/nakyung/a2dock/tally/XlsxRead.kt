package com.nakyung.a2dock.tally

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 간단한 xlsx 읽기 (첫 번째 시트, 값만) */
object XlsxRead {

    private fun local(n: String?): String = (n ?: "").substringAfter(':')

    private fun parser(b: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ByteArrayInputStream(b), "UTF-8")
        return p
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    private fun parseSst(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(b)
        var cur: StringBuilder? = null
        var inT = false
        var inRph = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "si" -> cur = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.TEXT -> if (inT && !inRph) cur?.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "si" -> { out.add(cur?.toString() ?: ""); cur = null }
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(b: ByteArray, sst: List<String>): List<Map<Int, String>> {
        val rows = ArrayList<Map<Int, String>>()
        val p = parser(b)
        var cur: HashMap<Int, String>? = null
        var col = -1
        var nextCol = 0
        var type: String? = null
        val buf = StringBuilder()
        var inV = false
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "row" -> { cur = HashMap(); nextCol = 0 }
                    "c" -> {
                        val r = p.getAttributeValue(null, "r")
                        col = if (r != null) colIndex(r) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t")
                        buf.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) buf.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = buf.toString()
                        val value = if (type == "s") sst.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: "" else raw
                        if (col >= 0) cur?.put(col, value.trim())
                    }
                    "row" -> { cur?.let { rows.add(it) }; cur = null }
                }
            }
            ev = p.next()
        }
        return rows
    }

    fun read(ins: InputStream): List<Map<Int, String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ins).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet"))
                    files[e.name] = z.readBytes()
            }
        }
        val sst = files["xl/sharedStrings.xml"]?.let { parseSst(it) } ?: emptyList()
        val sheetName = if (files.containsKey("xl/worksheets/sheet1.xml")) "xl/worksheets/sheet1.xml"
        else files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.minOrNull()
        val sheet = sheetName?.let { files[it] } ?: return emptyList()
        return parseSheet(sheet, sst)
    }
}
