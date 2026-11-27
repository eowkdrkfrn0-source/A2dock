package com.nakyung.a2dock.tally

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 엑셀 머리 정보 */
class XHeader(
    val dateSerial: Long, val carrier: String, val bk: String, val vsl: String, val shoring: String,
    val coa: String, val size: String, val bd: String, val existing: String
)

class XLot(val bd: Int, val lot: String, val qty: Double, val gw: Double)
class XCont(val cntr: String, val seal: String, val tare: String, val lots: List<XLot>) {
    var weight = 0.0
}

/** 고려아연 CLP 양식(template.xlsx)에 그대로 채워 넣는 엑셀 생성기 */
object XlsxExport {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private val numRe = Regex("""-?\d{1,15}(\.\d+)?""")
    private fun isNum(v: String) = numRe.matches(v)
    private fun numStr(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", d).trimEnd('0').trimEnd('.')
    private fun toD(v: String) = if (isNum(v.trim())) v.trim().toDouble() else 0.0

    private class B {
        val sb = StringBuilder()
        fun blank(ref: String, st: Int) { sb.append("<c r=\"$ref\" s=\"$st\"/>") }
        fun sst(ref: String, st: Int, i: Int) { sb.append("<c r=\"$ref\" s=\"$st\" t=\"s\"><v>$i</v></c>") }
        fun num(ref: String, st: Int, v: Double) { sb.append("<c r=\"$ref\" s=\"$st\"><v>${numStr(v)}</v></c>") }
        fun text(ref: String, st: Int, v: String) { sb.append("<c r=\"$ref\" s=\"$st\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>") }
        fun v(ref: String, st: Int, raw: String) {
            val x = raw.trim()
            when {
                x.isEmpty() -> blank(ref, st)
                isNum(x) -> num(ref, st, x.toDouble())
                else -> text(ref, st, x)
            }
        }
        fun fnum(ref: String, st: Int, f: String, v: Double) { sb.append("<c r=\"$ref\" s=\"$st\"><f>${esc(f)}</f><v>${numStr(v)}</v></c>") }
        fun fstr(ref: String, st: Int, f: String, v: String) { sb.append("<c r=\"$ref\" s=\"$st\" t=\"str\"><f>${esc(f)}</f><v>${esc(v)}</v></c>") }
        fun fv(ref: String, st: Int, f: String, raw: String) {
            val x = raw.trim()
            if (x.isNotEmpty() && isNum(x)) fnum(ref, st, f, x.toDouble()) else fstr(ref, st, f, x)
        }
        fun row(r: Int, attrs: String, body: B.() -> Unit) {
            sb.append("<row r=\"$r\" spans=\"1:11\"$attrs>"); body(); sb.append("</row>")
        }
    }

    fun build(template: InputStream, out: OutputStream, h: XHeader, conts: List<XCont>, per: Int) {
        val n = conts.size
        // ================= DETAILS =================
        val d = B()
        val merges = ArrayList<String>()
        val breaks = ArrayList<Int>()
        val totRows = ArrayList<Int>()
        d.row(1, " ht=\"30\" customHeight=\"1\"") { blank("A1", 5); blank("B1", 80); blank("C1", 5); blank("D1", 5) }
        val hv = mapOf(3 to h.carrier, 4 to h.bk, 5 to h.vsl, 6 to h.shoring)
        val hdrA = mapOf(2 to Pair(41, 17), 3 to Pair(41, 18), 4 to Pair(45, 2), 5 to Pair(46, 3), 6 to Pair(47, 19))
        for (r in 2..6) {
            val (st, si) = hdrA.getValue(r)
            d.row(r, " ht=\"30\" customHeight=\"1\"") {
                sst("A$r", st, si); blank("B$r", 81)
                if (r == 2) fnum("C2", 79, "CLP!C2", h.dateSerial.toDouble())
                else if (hv.getValue(r).isBlank()) blank("C$r", 44)
                else fv("C$r", 44, "CLP!C$r", hv.getValue(r))
                blank("D$r", 10); blank("I$r", 42); blank("J$r", 43)
            }
        }
        d.row(7, " ht=\"15\" customHeight=\"1\"") { blank("A7", 5); blank("B7", 80); blank("C7", 5); blank("D7", 5) }
        val H = " ht=\"49.5\" customHeight=\"1\""
        var top = 8
        for ((i, c) in conts.withIndex()) {
            val clpRow = 9 + i
            val t0 = top
            d.row(t0, H) {
                num("A$t0", 48, (i + 1).toDouble()); sst("B$t0", 82, 6); sst("C$t0", 48, 7); sst("D$t0", 20, 8)
                sst("E$t0", 19, 9); sst("F$t0", 23, 12); sst("G$t0", 49, 20); sst("H$t0", 50, 21)
                sst("I$t0", 51, 22); sst("J$t0", 51, 23); sst("K$t0", 52, 13)
            }
            for (k in 0 until per) {
                val r = t0 + 1 + k
                val lot = c.lots.getOrNull(k)
                d.row(r, H) {
                    if (k == 0) {
                        blank("A$r", 53)
                        fv("B$r", 93, "IF(A$t0=\"\",\"\",VLOOKUP(A$t0,CLP!A:D,2,FALSE()))", c.cntr)
                        fv("C$r", 54, "IF(A$t0=\"\",\"\",VLOOKUP(A$t0,CLP!A:D,3,FALSE()))", c.seal)
                        fv("D$r", 55, "IF(A$t0=\"\",\"\",VLOOKUP(A$t0,CLP!A:D,4,FALSE()))", c.tare)
                        v("E$r", 88, h.size)
                        fv("F$r", 56, "CLP!H$clpRow", h.coa)
                    } else {
                        blank("A$r", 53); blank("B$r", 83); blank("C$r", 53); blank("D$r", 61); blank("E$r", 88)
                        fv("F$r", 56, "F${r - 1}", h.coa)
                    }
                    if (lot != null) {
                        text("G$r", 57, lot.bd.toString()); text("H$r", 58, lot.lot)
                        num("I$r", 59, lot.qty); num("J$r", 60, lot.gw)
                    } else {
                        blank("G$r", 57); blank("H$r", 58); blank("I$r", 59); blank("J$r", 60)
                    }
                    if (k == 0) fnum("K$r", 58, "CLP!I$clpRow", 0.0) else fnum("K$r", 58, "K${r - 1}", 0.0)
                }
            }
            val e = t0 + per + 1
            d.row(e, H) {
                blank("A$e", 53); blank("B$e", 83); blank("C$e", 53); blank("D$e", 61); blank("E$e", 88)
                blank("F$e", 62); blank("G$e", 63); blank("H$e", 64); blank("I$e", 65); blank("J$e", 66); blank("K$e", 64)
            }
            val x = e + 1
            d.row(x, H) {
                blank("A$x", 53); blank("B$x", 83); blank("C$x", 53); blank("D$x", 61); blank("E$x", 88)
                sst("F$x", 89, 24); blank("G$x", 89); blank("H$x", 89); v("I$x", 59, h.existing); blank("J$x", 60); blank("K$x", 58)
            }
            val t = x + 1
            val qsum = c.lots.sumOf { it.qty } + toD(h.existing)
            val wsum = c.lots.sumOf { it.gw }
            c.weight = wsum
            d.row(t, H) {
                blank("A$t", 67); blank("B$t", 84); blank("C$t", 67); blank("D$t", 68); blank("E$t", 88)
                sst("F$t", 90, 25); blank("G$t", 90); blank("H$t", 90)
                fnum("I$t", 69, "SUM(I${t0 + 1}:I$x)", qsum); fnum("J$t", 70, "SUM(J${t0 + 1}:J$x)", wsum); blank("K$t", 71)
            }
            totRows.add(t)
            merges.add("E${t0 + 1}:E$t"); merges.add("F$x:H$x"); merges.add("F$t:H$t")
            if (i % 3 == 2 && i != n - 1) breaks.add(t)
            val sp = t + 1
            if (i != n - 1) {
                d.row(sp, " ht=\"20.25\" customHeight=\"1\"") {
                    blank("A$sp", 5); blank("B$sp", 80); blank("C$sp", 5); blank("D$sp", 5); blank("I$sp", 43); blank("J$sp", 43)
                }
            }
            top = sp + 1
        }
        val lastD = totRows.lastOrNull() ?: 8

        // ================= CLP =================
        val p = B()
        val R = " s=\"5\" customFormat=\"1\" ht=\"20.25\" customHeight=\"1\""
        p.row(1, " s=\"5\" customFormat=\"1\"") { blank("F1", 6); blank("G1", 7) }
        // r -> (A스타일, 문자열번호, C스타일, H/I스타일)
        val cst = mapOf(
            2 to intArrayOf(8, 0, 78, 12), 3 to intArrayOf(8, 1, 13, 14), 4 to intArrayOf(15, 2, 13, 14),
            5 to intArrayOf(16, 3, 13, 12), 6 to intArrayOf(17, 4, 18, 14)
        )
        for (r in 2..6) {
            val a = cst.getValue(r)
            p.row(r, R) {
                sst("A$r", a[0], a[1]); blank("B$r", 9)
                if (r == 2) num("C2", 78, h.dateSerial.toDouble()) else v("C$r", a[2], hv.getValue(r))
                blank("D$r", 10); blank("F$r", 6); blank("G$r", 11); blank("H$r", a[3]); blank("I$r", a[3])
            }
        }
        p.row(7, R) { blank("F7", 6); blank("G7", 7); sst("J7", 91, 5); blank("K7", 91) }
        p.row(8, " s=\"3\" customFormat=\"1\" ht=\"20.25\" customHeight=\"1\"") {
            blank("A8", 19); sst("B8", 19, 6); sst("C8", 19, 7); sst("D8", 20, 8); sst("E8", 21, 9); sst("F8", 22, 10)
            sst("G8", 19, 11); sst("H8", 23, 12); sst("I8", 24, 13); sst("J8", 25, 14); sst("K8", 26, 15)
        }
        for ((i, c) in conts.withIndex()) {
            val r = 9 + i
            p.row(r, " s=\"3\" customFormat=\"1\"") {
                num("A$r", 21, (i + 1).toDouble()); v("B$r", 27, c.cntr); v("C$r", 27, c.seal); v("D$r", 28, c.tare)
                v("E$r", 21, h.size); v("F$r", 29, h.bd); fnum("G$r", 30, "DETAILS!J${totRows[i]}", c.weight)
                v("H$r", 27, h.coa); blank("I$r", 27); blank("J$r", 31); fnum("K$r", 31, "D$r+J$r", toD(c.tare))
            }
        }
        val s = 9 + n
        val last = 8 + n
        p.row(s, "") {
            blank("A$s", 32); blank("B$s", 33); blank("C$s", 33); blank("D$s", 34); blank("E$s", 35)
            fnum("F$s", 36, "SUM(F9:F$last)", toD(h.bd) * n)
            fnum("G$s", 37, "SUM(G9:G$last)", conts.sumOf { it.weight })
            blank("H$s", 38); blank("I$s", 38)
        }
        val s1 = s + 1
        p.row(s1, "") { blank("F$s1", 39); blank("H$s1", 1); blank("I$s1", 1) }

        val mc = "<mergeCells count=\"${merges.size}\">" + merges.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>"
        val rb = if (breaks.isEmpty()) "" else
            "<rowBreaks count=\"${breaks.size}\" manualBreakCount=\"${breaks.size}\">" +
                    breaks.joinToString("") { "<brk id=\"$it\" max=\"16383\" man=\"1\"/>" } + "</rowBreaks>"
        val rep = mapOf(
            "xl/worksheets/sheet1.xml" to mapOf("__SD__" to p.sb.toString(), "__DIM__" to "A1:K$s1"),
            "xl/worksheets/sheet2.xml" to mapOf("__SD__" to d.sb.toString(), "__DIM__" to "A1:K$lastD", "__MC__" to mc, "__RB__" to rb),
            "xl/workbook.xml" to mapOf("__PA1__" to "CLP!\$A\$1:\$I\$$s1", "__PA2__" to "DETAILS!\$A\$1:\$K\$$lastD")
        )

        val zin = ZipInputStream(template)
        val zout = ZipOutputStream(out)
        while (true) {
            val e = zin.nextEntry ?: break
            val bos = ByteArrayOutputStream()
            zin.copyTo(bos)
            var bytes = bos.toByteArray()
            val m = rep[e.name]
            if (m != null) {
                var txt = String(bytes, Charsets.UTF_8)
                for ((k, v) in m) txt = txt.replace(k, v)
                bytes = txt.toByteArray(Charsets.UTF_8)
            }
            zout.putNextEntry(ZipEntry(e.name))
            zout.write(bytes)
            zout.closeEntry()
        }
        zin.close()
        zout.finish()
        zout.flush()
    }
}
