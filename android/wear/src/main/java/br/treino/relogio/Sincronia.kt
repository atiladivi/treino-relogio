package br.treino.relogio

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * A mesma lógica da página do relógio (ponte-relogio/Relogio.html), em Kotlin:
 * lê o estado que o celular gravou (pela ponte, ?api=estado) e manda os toques (?api=comando).
 * O toque feito aqui aparece na hora ("otimista") e vale até o celular confirmar que leu (estado.cmd).
 */
object Sincronia {
    private const val API =
        "https://script.google.com/macros/s/AKfycbwmfrUswieUGZP-ms-GY7u1oTwAvmtoFSEHvQfT8Xul1__msj6ptuOFDD2UbsliVQPI/exec"
    private const val MAX_TOQUE = 3 * 60000L   // toque que não saiu em 3 min: descartado (o treino já seguiu)
    private const val PREFS = "relogio"

    class Pend(val estado: JSONObject, val em: Long, var id: Long)

    @Volatile var chave = ""
    private var srv: JSONObject? = null
    private var pend: Pend? = null
    private val fila = ArrayList<JSONObject>()
    var parado = false; private set      // o celular parou de ler os toques
    var ultOk = 0L; private set
    var erro = false; private set
    var avisoAte = 0L; private set       // até quando mostrar "toque antigo descartado"
    var flashDesde = 0L; private set     // última vez que a tela deve piscar (fim do descanso / celular parado)
    @Volatile var visivel = false        // a tela do app está aberta
    private var bipou = 0L
    private var paradoDesde = 0L
    private var avisouParado = 0L

    private val leitura = Executors.newSingleThreadExecutor()
    private val envio = Executors.newSingleThreadExecutor()

    fun iniciar(ctx: Context) {
        if (chave.isEmpty()) chave = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("chave", "") ?: ""
    }

    fun guardarChave(ctx: Context, k: String) {
        chave = k.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("chave", chave).apply()
    }

    // ---------- rede ----------

    private fun pedir(params: Map<String, String>): JSONObject {
        val q = params.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = URL("$API?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000; c.useCaches = false
        c.instanceFollowRedirects = true // o Apps Script responde com um redirecionamento (https → https)
        try {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            if (j.has("erro")) throw IOException(j.optString("erro"))
            return j
        } finally {
            c.disconnect()
        }
    }

    /** Lê o estado do celular e, depois, reenvia os toques guardados (se ainda fizerem sentido). */
    fun buscar() = leitura.execute {
        if (chave.isEmpty()) return@execute
        try {
            val r = pedir(mapOf("api" to "estado", "k" to chave))
            synchronized(this) {
                srv = r.optJSONObject("estado") ?: JSONObject()
                ultOk = System.currentTimeMillis()
                val vivo = r.optLong("vivo"); val agora = r.optLong("agora")
                parado = vivo > 0 && agora - vivo > 12000
                erro = fila.isNotEmpty()
            }
        } catch (e: Exception) {
            synchronized(this) { erro = true }
        }
        enviarFila()
    }

    /** Entrega os toques guardados, na ordem; se a rede falhar, ficam para a próxima. */
    fun enviarFila() = envio.execute {
        descartarVelhos()
        while (true) {
            val cmd = synchronized(this) { fila.firstOrNull() } ?: break
            try {
                val p = HashMap<String, String>()
                p["api"] = "comando"; p["k"] = chave
                cmd.keys().forEach { p[it] = cmd.get(it).toString() }
                val id = pedir(p).optLong("id")
                synchronized(this) {
                    fila.remove(cmd)
                    if (fila.isEmpty()) { pend?.id = id; erro = false } // o celular confirma quando passar desse id
                }
            } catch (e: Exception) {
                synchronized(this) { erro = true }
                break
            }
        }
    }

    /** Joga fora os toques guardados que perderam o sentido (velhos, ou o celular mexeu no treino depois deles).
     *  Os toques são encadeados: se um cai, caem todos. */
    @Synchronized private fun descartarVelhos() {
        if (fila.isEmpty()) return
        val agora = System.currentTimeMillis()
        val ev = srv?.optLong("ev") ?: 0L
        if (fila.none { agora - it.optLong("em") > MAX_TOQUE || ev > it.optLong("em") + 5000 }) return
        fila.clear()
        if (pend?.id == 0L) pend = null
        avisoAte = agora + 8000
    }

    // ---------- estado ----------

    /** O que mostrar: o toque feito aqui vale até o celular ler os toques. */
    @Synchronized fun atual(): JSONObject? {
        pend?.let { p ->
            val s = srv
            val lido = s != null && (if (s.has("cmd") && !s.isNull("cmd")) p.id != 0L && s.optLong("cmd") >= p.id
                                     else s.optLong("ts") > p.em)
            if (lido || System.currentTimeMillis() - p.em > 15 * 60000L) pend = null else return p.estado
        }
        return srv
    }

    @Synchronized fun temFila() = fila.isNotEmpty()

    fun emTreino(e: JSONObject?): Boolean {
        if (e == null) return false
        val modo = e.optString("modo")
        return modo.isNotEmpty() && modo != "nenhum" && System.currentTimeMillis() - e.optLong("ts") < 4 * 3600e3
    }

    /** Toque no botão grande ("✓ Terminei" ou "▶ nª série"). Devolve false se não havia o que fazer. */
    fun tocar(): Boolean {
        synchronized(this) {
            val e = atual() ?: return false
            val modo = e.optString("modo")
            if (modo.isEmpty() || modo == "nenhum" || modo == "fim" || modo == "espera" || e.optBoolean("pausado")) return false
            val em = System.currentTimeMillis()
            val serie = e.optInt("serie"); val total = e.optInt("total")
            val cmd = JSONObject().put("ex", e.optInt("ex")).put("nome", e.optString("nome")).put("em", em)
            val novo = JSONObject(e.toString())
            if (modo == "exec") {
                cmd.put("acao", "terminei").put("serie", serie)
                val ultima = total > 0 && serie >= total
                val feitos = e.optInt("feitos") + 1
                if (!ultima) {
                    novo.put("modo", "desc").put("troca", false).put("desde", em)
                } else if (e.optString("proxNome").isNotEmpty()) {
                    // última série: segue para o próximo que o celular informou; o seguinte a ele só o celular sabe
                    novo.put("modo", "desc").put("troca", true).put("desde", em).put("nome", e.optString("proxNome"))
                        .put("serie", 0).put("total", e.optInt("proxTotal")).put("alvo", e.optInt("proxAlvo"))
                        .put("ex", -1).put("feitos", feitos)
                        .put("proxNome", "").put("proxTotal", 0).put("proxAlvo", 0).put("proxDesconhecido", true)
                } else {
                    val espera = e.optBoolean("proxDesconhecido") && feitos < e.optInt("nItens")
                    novo.put("modo", if (espera) "espera" else "fim").put("desde", em).put("feitos", feitos)
                }
            } else {
                cmd.put("acao", "comecar").put("serie", serie + 1)
                novo.put("modo", "exec").put("desde", em).put("serie", serie + 1)
            }
            pend = Pend(novo, em, 0)
            fila.add(cmd)
        }
        enviarFila()
        return true
    }

    enum class Alerta { NENHUM, DESCANSO, PARADO }

    /** Chamado a cada meio segundo pelo serviço: fim do descanso (uma vez por descanso) e celular parado
     *  (ao parar e de novo a cada 2 min). */
    @Synchronized fun alerta(): Alerta {
        val e = atual(); val agora = System.currentTimeMillis()
        val treino = emTreino(e)
        if (!(parado && treino)) { paradoDesde = 0; avisouParado = 0 }
        else {
            if (paradoDesde == 0L) paradoDesde = agora
            if (agora - avisouParado >= 120000) { avisouParado = agora; flashDesde = agora; return Alerta.PARADO }
        }
        if (treino && e!!.optString("modo") == "desc" && !e.optBoolean("pausado")) {
            val alvo = e.optLong("alvo") * 1000; val desde = e.optLong("desde"); val dt = agora - desde
            if (alvo > 0 && dt >= alvo && dt < alvo + 15000 && bipou != desde) {
                bipou = desde; flashDesde = agora; return Alerta.DESCANSO
            }
        }
        return Alerta.NENHUM
    }

    // ---------- o que desenhar ----------

    class Visual {
        var rot = ""; var rotVerde = false; var nome = ""; var t = "–:––"; var alvo = ""
        var arco = 0f            // 0..1 (parte cheia do anel)
        var corArco = 0          // 0 = sem arco, 1 = série (azul), 2 = descanso (verde), 3 = passou (vermelho)
        var botao = ""; var botaoVerde = false
        var ponto = 0            // 0 cinza, 1 ok, 2 erro, 3 celular parado
        var info = ""
    }

    private fun fmt(ms: Long): String { val s = maxOf(0L, ms / 1000); return "${s / 60}:" + (s % 60).toString().padStart(2, '0') }

    fun visual(telaPresa: Boolean): Visual {
        val v = Visual()
        val e = atual(); val agora = System.currentTimeMillis()
        val velho = e == null || e.optLong("ts") == 0L || agora - e.optLong("ts") > 4 * 3600e3
        val modo = e?.optString("modo") ?: ""
        val treino = modo.isNotEmpty() && modo != "nenhum"
        v.ponto = if (erro) 2 else if (parado && treino) 3 else if (ultOk > 0) 1 else 0
        when {
            chave.isEmpty() -> { v.rot = "Sem chave"; v.nome = "Instale a chave pelo PC"; v.t = "—" }
            e == null || velho || modo == "nenhum" -> {
                val tentou = ultOk > 0 || erro
                v.rot = if (tentou) "Sem treino" else "Conectando…"
                v.nome = if (tentou) "Abra o treino no celular" else ""
                v.t = "—"
            }
            modo == "fim" -> { v.rot = "Tudo feito"; v.nome = "Conclua no celular"; v.t = "✓" }
            modo == "espera" -> { v.rot = "Aguardando o celular"; v.nome = "Abra o app no celular"; v.t = "…" }
            else -> {
                v.nome = e.optString("nome")
                val serie = e.optInt("serie"); val total = e.optInt("total")
                val de = if (total > 0) " de $total" else ""
                val desde = e.optLong("desde")
                when (modo) {
                    "exec" -> {
                        v.rot = "Série $serie$de"; v.corArco = 1; v.arco = 1f; v.t = fmt(agora - desde)
                        v.botao = "✓ Terminei"
                    }
                    "desc" -> {
                        val dt = agora - desde
                        val troca = e.optBoolean("troca")
                        v.rot = if (troca) "Próximo" else "Descanso · ${serie}ª feita"; v.rotVerde = troca
                        v.t = fmt(dt)
                        val alvo = e.optLong("alvo") * 1000
                        if (alvo > 0) {
                            v.alvo = "ficha " + fmt(alvo)
                            v.arco = 1f - minOf(1f, dt.toFloat() / alvo)
                            v.corArco = if (dt > alvo) 3 else 2
                        } else { v.arco = 1f; v.corArco = 2 }
                        v.botao = "▶ ${serie + 1}ª série"; v.botaoVerde = true
                    }
                    else -> { v.rot = "Primeiro"; v.t = "0:00"; v.botao = "▶ 1ª série"; v.botaoVerde = true }
                }
                if (e.optBoolean("pausado")) { v.rot = "Pausado no celular"; v.botao = "" }
            }
        }
        val fila = temFila()
        v.info = when {
            fila -> if (agora - ultOk < 10000) "enviando toque…" else "sem rede · toque guardado"
            avisoAte > agora -> "toque antigo descartado"
            erro -> "sem rede"
            parado && treino -> "celular parado " + fmt(agora - (if (paradoDesde > 0) paradoDesde else agora)) + " · abra o app"
            e != null && !velho && e.optInt("nItens") > 0 -> "${e.optInt("feitos")}/${e.optInt("nItens")} feitos" + if (telaPresa) " ☼" else ""
            else -> ""
        }
        return v
    }
}
