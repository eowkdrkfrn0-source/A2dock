package com.nakyung.a2dock.tally

import org.json.JSONArray
import org.json.JSONObject

/**
 * 탈리 공유: 3-way 병합 (base = 마지막으로 서버와 맞춘 상태, local = 이 폰, server = 서버)
 * - 패킹리스트·컨목록·범위·머리글·보낸시각: 발신번호별로, 한쪽만 바꿨으면 그쪽, 둘 다 바꿨으면 이 폰 것
 * - 스캔: 이 폰에서 새로 찍은 건 더하고, 이 폰에서 지운 건 빼고, 다른 폰이 찍은 건 그대로
 * - 컨 지정: 이 폰에서 바꾼 묶음만 반영
 */
object TallyMerge {
    private fun keyed(arr: JSONArray?, key: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        if (arr != null) for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; m[o.optString(key)] = o.toString() }
        return m
    }
    private fun objMap(o: JSONObject?): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        o?.keys()?.forEach { k -> m[k] = o.get(k).toString() }
        return m
    }
    private fun merge3(b: Map<String, String>, l: Map<String, String>, s: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val keys = LinkedHashSet<String>().apply { addAll(s.keys); addAll(l.keys); addAll(b.keys) }
        for (k in keys) {
            val bv = b[k]; val lv = l[k]; val sv = s[k]
            val r = if (lv == bv) sv else lv     // 이 폰이 안 바꿨으면 서버 것, 바꿨으면 이 폰 것 (지운 것도 포함)
            if (r != null) out[k] = r
        }
        return out
    }

    private class G(val coa: String, val bds: MutableList<Int>, var cntr: String)
    private fun groups(o: JSONObject): MutableList<G> {
        val a = o.optJSONArray("groups") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val g = a.getJSONObject(i); val bd = g.optJSONArray("bds") ?: JSONArray()
            G(g.optString("coa"), MutableList(bd.length()) { bd.getInt(it) }, g.optString("cntr"))
        }.toMutableList()
    }
    private fun keys(gs: List<G>): LinkedHashSet<String> = LinkedHashSet<String>().apply { for (g in gs) for (b in g.bds) add("${g.coa}:$b") }
    private fun hist(o: JSONObject): List<String> { val a = o.optJSONArray("hist") ?: JSONArray(); return (0 until a.length()).map { a.getString(it) } }

    fun merge(baseS: String?, localS: String, serverS: String?, per: Int = 5): String {
        val B = try { JSONObject(baseS ?: "{}") } catch (e: Exception) { JSONObject() }
        val L = JSONObject(localS)
        val S = try { JSONObject(serverS ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = JSONObject()
        // 발신번호별 자료
        for ((f, k) in listOf("bookings" to "coa", "clists" to "coa")) {
            val m = merge3(keyed(B.optJSONArray(f), k), keyed(L.optJSONArray(f), k), keyed(S.optJSONArray(f), k))
            out.put(f, JSONArray(m.values.map { JSONObject(it) }))
        }
        for (f in listOf("ranges", "hdr", "exported")) {
            val m = merge3(objMap(B.optJSONObject(f)), objMap(L.optJSONObject(f)), objMap(S.optJSONObject(f)))
            val o = JSONObject(); for ((k, v) in m) o.put(k, if (v.startsWith("[")) JSONArray(v) else if (v.startsWith("{")) JSONObject(v) else v)
            out.put(f, o)
        }
        val rt = if (L.optString("rangeText") == B.optString("rangeText")) S.optString("rangeText") else L.optString("rangeText")
        out.put("rangeText", rt)

        // 스캔
        val gb = groups(B); val gl = groups(L); val gs = groups(S)
        val kb = keys(gb); val kl = keys(gl)
        val removed = kb - kl; val added = kl - kb
        val res = gs.map { G(it.coa, it.bds.toMutableList(), it.cntr) }.toMutableList()
        for (g in res) g.bds.removeAll { "${g.coa}:$it" in removed }
        val have = keys(res)
        val made = HashMap<G, G>()
        for (g in gl) for (bd in g.bds) {
            val key = "${g.coa}:$bd"
            if (key !in added || key in have) continue
            val others = g.bds.filter { it != bd }.toSet()
            var t = made[g]?.takeIf { it.bds.size < per }
                ?: res.firstOrNull { r -> r.coa == g.coa && r.bds.size < per && r.bds.any { it in others } }
            if (t == null) { t = G(g.coa, mutableListOf(), g.cntr); res.add(t); made[g] = t }
            t.bds.add(bd); have.add(key)
        }
        // 컨 지정 (이 폰에서 바꾼 것만)
        for (g in gl) {
            val bset = g.bds.toSet()
            val base = gb.firstOrNull { it.coa == g.coa && it.bds.toSet() == bset }
            if (base != null && base.cntr == g.cntr) continue
            val r = res.firstOrNull { it.coa == g.coa && it.bds.toSet() == bset } ?: res.firstOrNull { it.coa == g.coa && it.bds.any { b -> b in bset } }
            if (r != null && (base != null || r.cntr.isEmpty())) r.cntr = g.cntr
        }
        // 같은 컨이 두 묶음에 붙으면 이 폰 쪽만 남김
        val usedC = HashMap<String, G>()
        for (r in res) if (r.cntr.isNotEmpty()) { val k = r.coa + "|" + r.cntr; val p = usedC[k]; if (p == null) usedC[k] = r else r.cntr = "" }
        val kept = res.filter { it.bds.isNotEmpty() }
        out.put("groups", JSONArray(kept.map { JSONObject().put("coa", it.coa).put("bds", JSONArray(it.bds)).put("cntr", it.cntr) }))
        val final = keys(kept)
        val h = LinkedHashSet<String>()
        for (x in hist(S)) if (x in final) h.add(x)
        for (x in hist(L)) if (x in final) h.add(x)
        for (x in final) h.add(x)
        out.put("hist", JSONArray(h.toList()))
        return out.toString()
    }
}
