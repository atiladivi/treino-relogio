package br.treino.relogio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlin.math.asin

/**
 * Tela do relógio (mockup claude.ai/artifact/LcLXFGQspEKgsC5AmMkQHd): botão em meia-lua embaixo,
 * arco do descanso na borda, fundo tingido pelo estado, bolinhas das séries.
 * Com treino aberto a tela fica sempre ligada; sem treino, pulso baixado = modo ambiente.
 * A chave da ponte chega uma vez pelo PC:
 *   adb shell am start -n br.treino.relogio/.MainActivity --es chave <CHAVE>
 */
class MainActivity : ComponentActivity() {

    private lateinit var tela: Tela
    private val h = Handler(Looper.getMainLooper())

    private var telaPresa = false

    private val tique = object : Runnable {
        override fun run() {
            // com treino aberto a tela não apaga (pedido do usuário); sem treino, o relógio apaga normalmente
            val treino = Sincronia.emTreino(Sincronia.atual())
            if (treino != telaPresa) {
                telaPresa = treino
                if (treino) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            tela.invalidate()
            h.postDelayed(this, if (tela.ambiente) 1000 else 250)
        }
    }

    private val ambiente = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { tela.ambiente = true; tela.invalidate() }
        override fun onUpdateAmbient() { tela.invalidate() }
        override fun onExitAmbient() { tela.ambiente = false; tela.invalidate(); Sincronia.buscar() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Sincronia.iniciar(this)
        lerChave(intent)
        tela = Tela(this) { if (Sincronia.tocar()) { tela.performHapticFeedback(HapticFeedbackConstants.CONFIRM); tela.invalidate() } }
        setContentView(tela)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambiente))
        // notificação (ícone no mostrador) e sensores do corpo (batimentos): pedidas uma vez, na tela do relógio
        val faltam = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BODY_SENSORS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltam.isNotEmpty()) requestPermissions(faltam.toTypedArray(), 1)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        lerChave(i)
    }

    private fun lerChave(i: Intent?) {
        val k = i?.getStringExtra("chave") ?: return
        if (k.isNotBlank()) Sincronia.guardarChave(this, k)
    }

    override fun onStart() {
        super.onStart()
        Sincronia.visivel = true
        RelogioService.iniciar(this)
        Sincronia.buscar()
        h.post(tique)
    }

    override fun onStop() {
        Sincronia.visivel = false
        h.removeCallbacks(tique)
        super.onStop()
    }

    /** Fechou o app (botão voltar) sem treino aberto: o serviço para na hora (com treino, segue para vibrar). */
    override fun onDestroy() {
        if (isFinishing && !Sincronia.emTreino(Sincronia.atual())) stopService(Intent(this, RelogioService::class.java))
        super.onDestroy()
    }
}

/** Desenha tudo em unidades de um relógio de 480 px (o mockup) e escala para a tela real. */
class Tela(ctx: Context, private val aoTocar: () -> Unit) : View(ctx) {

    var ambiente = false
    private var apertado = false

    private fun cor(s: String) = Color.parseColor(s)
    private val AZUL = cor("#4f8ff7"); private val VERDE = cor("#34c27a"); private val VERMELHO = cor("#f05a4f")
    private val AMARELO = cor("#f2c14e"); private val TINTA = cor("#f2f4f7"); private val TINTA2 = cor("#9aa4b5")
    private val CINZA = cor("#8a93a3"); private val CINZA_ESC = cor("#3a414d")

    // por fase: 0 nada, 1 série, 2 descanso, 3 estourado, 4 primeiro/próximo
    // mesmas cores do app do celular (tema escuro): série = verde, descanso = dourado, passou = vermelho, pronto = azul
    private val FUNDO = intArrayOf(Color.BLACK, cor("#0f2a1d"), cor("#2d2512"), cor("#2a0b09"), cor("#101a2e"))
    private val ROTULO = intArrayOf(cor("#8db8ff"), cor("#7fdcae"), cor("#f7d77f"), cor("#ff9a90"), cor("#8db8ff"))
    private val ARCO = intArrayOf(0, cor("#4cc38a"), cor("#f2c14e"), cor("#f97066"), cor("#6ea2f5"))

    private val txt = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val nomeP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val arcoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val MEDIO = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val NEGRITO = Typeface.create("sans-serif", Typeface.BOLD)

    private val ALTURA_BOTAO = 110f   // em px do mockup (480)
    private var topoBotao = Float.MAX_VALUE
    private var temBotao = false
    private var nomeCache = ""; private var nomeLarg = 0; private var nomeLayout: StaticLayout? = null
    private val circulo = Path()

    private fun texto(c: Canvas, s: String, x: Float, y: Float, tam: Float, cor: Int, larg: Float, tf: Typeface, esp: Float = 0f) {
        txt.textSize = tam; txt.color = cor; txt.typeface = tf; txt.letterSpacing = esp
        c.drawText(TextUtils.ellipsize(s, txt, larg, TextUtils.TruncateAt.END).toString(), x, y, txt)
    }

    override fun onDraw(c: Canvas) {
        val v = Sincronia.visual()
        val s = minOf(width, height).toFloat()
        val k = s / 480f
        val ox = (width - s) / 2; val oy = (height - s) / 2
        fun X(u: Float) = ox + u * k
        fun Y(u: Float) = oy + u * k
        val cx = X(240f)
        val amb = ambiente

        // fundo tingido pelo estado; pisca em vermelho no fim do descanso / celular parado
        var fundo = if (amb) Color.BLACK else FUNDO[v.fase]
        val df = System.currentTimeMillis() - Sincronia.flashDesde
        if (!amb && df in 0..2399) {
            val f = (df % 1200) / 1200f
            val q = if (f < .3f) f / .3f else (1 - f) / .7f
            fundo = misturar(fundo, cor("#5a1410"), q)
        }
        c.drawColor(fundo)

        temBotao = v.botao.isNotEmpty()
        val topo = 480f - ALTURA_BOTAO

        // arco da borda (parte de cima, até pouco acima da meia-lua)
        if (v.fase != 0) {
            val r = 226f
            val yFim = (if (temBotao) topo - 14f else 330f) - 240f
            val ang = Math.toDegrees(asin((yFim / r).toDouble())).toFloat() // abaixo da horizontal
            val ini = 180f - ang; val total = 180f + 2 * ang
            val oval = RectF(X(240f - r), Y(240f - r), X(240f + r), Y(240f + r))
            arcoP.strokeWidth = (if (amb) 6f else 14f) * k
            arcoP.color = if (amb) cor("#1a1d23") else Color.argb(20, 255, 255, 255)
            c.drawArc(oval, ini, total, false, arcoP)
            if (v.arco > 0f) {
                arcoP.color = if (amb) CINZA else ARCO[v.fase]
                arcoP.alpha = if (!amb && v.fase == 1) 115 else 255
                c.drawArc(oval, ini, total * v.arco.coerceIn(0f, 1f), false, arcoP)
                arcoP.alpha = 255
            }
        }

        // bolinhas das séries + ponto da conexão
        run {
            val n = v.series
            val d = 14f; val dA = 18f; val gap = 10f; val dc = 8f
            val larg = (if (n > 0) n * d + (dA - d) + (n - 1) * gap + 8f + gap else 0f) + dc
            var x = 240f - larg / 2
            val y = 51f
            for (i in 0 until n) {
                when {
                    i < v.feitas -> { p.style = Paint.Style.FILL; p.color = if (amb) cor("#5b6372") else TINTA
                        c.drawCircle(X(x + d / 2), Y(y), d / 2 * k, p); x += d + gap }
                    i == v.feitas -> { p.style = Paint.Style.STROKE; p.strokeWidth = 3f * k
                        p.color = if (amb) CINZA else ARCO[maxOf(1, v.fase)]
                        c.drawCircle(X(x + dA / 2), Y(y), (dA - 3f) / 2 * k, p); x += dA + gap }
                    else -> { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * k   // por fazer: aro na cor do estado
                        p.color = if (amb) CINZA_ESC else ARCO[maxOf(1, v.fase)]
                        c.drawCircle(X(x + d / 2), Y(y), (d - 2.5f) / 2 * k, p); x += d + gap }
                }
            }
            if (n > 0) x += 8f
            p.style = Paint.Style.FILL
            p.color = if (amb) CINZA_ESC else when (v.ponto) { 1 -> VERDE; 2 -> VERMELHO; 3 -> AMARELO; else -> CINZA_ESC }
            c.drawCircle(X(x + dc / 2), Y(y), dc / 2 * k, p)
        }

        // rótulo e nome (até 2 linhas)
        texto(c, v.rot, cx, Y(98f), 18f * k, if (amb) CINZA else ROTULO[v.fase], 300f * k, NEGRITO, .08f)
        val nl = desenharNome(c, v.nome, cx, Y(134f), k, if (amb) cor("#aab2c0") else TINTA)

        // tempo grande e a linha de baixo
        val yT = if (nl > 1) 266f else 258f
        txt.textSize = 104f * k; txt.typeface = NEGRITO; txt.letterSpacing = -.02f; txt.fontFeatureSettings = "tnum"
        if (amb) {
            txt.style = Paint.Style.STROKE; txt.strokeWidth = 2f * k; txt.color = cor("#aab2c0")
        } else {
            txt.style = Paint.Style.FILL; txt.color = if (v.fase == 3) cor("#ff7b70") else TINTA
        }
        c.drawText(v.t, cx, Y(yT), txt)
        txt.style = Paint.Style.FILL; txt.fontFeatureSettings = null
        if (v.sub.isNotEmpty())
            texto(c, v.sub, cx, Y(yT + 42f), 22f * k, if (v.aviso && !amb) AMARELO else TINTA2, 330f * k, MEDIO)

        // batimentos (leitura dos últimos 15 s), entre o tempo e a meia-lua
        if (v.fase != 0 && Sincronia.fc > 0 && System.currentTimeMillis() - Sincronia.fcEm < 15000) {
            val num = Sincronia.fc.toString()
            txt.textSize = 26f * k; txt.typeface = NEGRITO; txt.letterSpacing = 0f
            val lc = txt.measureText("♥"); val ln = txt.measureText(num); val esp = 6f * k
            val x0 = cx - (lc + esp + ln) / 2; val yF = Y(if (nl > 1) 352f else 346f)
            txt.color = if (amb) CINZA else cor("#ff6b6b"); c.drawText("♥", x0 + lc / 2, yF, txt)
            txt.color = if (amb) CINZA else TINTA; c.drawText(num, x0 + lc + esp + ln / 2, yF, txt)
        }

        // meia-lua
        topoBotao = Y(topo)
        if (temBotao) {
            circulo.reset(); circulo.addCircle(cx, Y(240f), s / 2, Path.Direction.CW)
            c.save(); c.clipPath(circulo)
            if (amb) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * k; p.color = CINZA_ESC
                c.drawLine(ox, Y(topo), ox + s, Y(topo), p)
            } else {
                p.style = Paint.Style.FILL
                p.color = ARCO[maxOf(1, v.fase)]
                if (apertado) p.color = misturar(p.color, Color.BLACK, .25f)
                c.drawRect(ox, Y(topo), ox + s, oy + s, p)
            }
            c.restore()
            val corTxt = if (amb) CINZA else cor("#0b1220")
            texto(c, v.botao, cx, Y(topo + ALTURA_BOTAO * .5f), 32f * k, corTxt, 260f * k, NEGRITO)
        }
    }

    /** Nome do exercício em até 2 linhas, centrado; devolve quantas linhas usou. */
    private fun desenharNome(c: Canvas, nome: String, cx: Float, baseline: Float, k: Float, cor: Int): Int {
        if (nome.isEmpty()) return 0
        val larg = (340f * k).toInt()
        if (nome != nomeCache || larg != nomeLarg || nomeLayout == null) {
            nomeP.textSize = 30f * k
            nomeLayout = StaticLayout.Builder.obtain(nome, 0, nome.length, nomeP, larg)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.05f).build()
            nomeCache = nome; nomeLarg = larg
        }
        val l = nomeLayout!!
        nomeP.color = cor
        c.save(); c.translate(cx - larg / 2f, baseline - l.getLineBaseline(0)); l.draw(c); c.restore()
        return l.lineCount
    }

    private fun misturar(a: Int, b: Int, q: Float): Int {
        val t = q.coerceIn(0f, 1f)
        return Color.rgb((Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dentro = temBotao && !ambiente && e.y >= topoBotao - 12f * minOf(width, height) / 480f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { apertado = dentro; invalidate() }
            MotionEvent.ACTION_UP -> { val era = apertado; apertado = false; invalidate(); if (era && dentro) aoTocar() }
            MotionEvent.ACTION_CANCEL -> { apertado = false; invalidate() }
        }
        return true
    }
}
