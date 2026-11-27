package br.treino.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

/**
 * App do treino: a mesma tela do app web (hospedada no GitHub Pages) dentro de um WebView, sem barra
 * de endereço. O que só um app consegue fica aqui e no TreinoService:
 *  - com treino rodando, um serviço em primeiro plano mantém o app vivo com a tela apagada (o Chrome
 *    parava de ler os toques do relógio quando o celular dormia);
 *  - notificação com o cronômetro e o botão da série; vibração/som no fim do descanso; tela ligada;
 *  - botão voltar fecha painéis em vez de sair.
 * A página nunca é "pausada" (webView.onPause não é chamado): os cronômetros e a sincronia continuam.
 */
class MainActivity : Activity() {

    companion object {
        const val URL_APP = "https://atiladivi.github.io/treino-relogio/app/"
        @Volatile var instancia: MainActivity? = null
    }

    private lateinit var web: WebView
    private val laco = Handler(Looper.getMainLooper())
    @Volatile private var ativo = false
    /** Com treino rodando, chama a página a cada 3 s: com o app em segundo plano o WebView pode segurar
     *  os setInterval da página; esta chamada vem de fora e garante a leitura dos toques do relógio. */
    private val tique = object : Runnable {
        override fun run() {
            if (!ativo) return
            web.evaluateJavascript("window.tickAndroid && window.tickAndroid()", null)
            laco.postDelayed(this, 3000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instancia = this
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // bipe do fim do descanso
            cacheMode = if (online()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        web.addJavascriptInterface(Ponte(), "Android")
        web.webViewClient = object : WebViewClient() {
            // páginas de fora abrem no navegador
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == Uri.parse(URL_APP).host) return false
                startActivity(Intent(Intent.ACTION_VIEW, req.url))
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { voltar() }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        abrir(intent)
    }

    /** Abre o app; com um link treino://chave/XXXX, entrega a chave à página (ela grava e tira do endereço). */
    private fun abrir(i: Intent?) {
        val d = i?.data
        val chave = if (d?.scheme == "treino" && d.host == "chave") d.lastPathSegment else null
        web.loadUrl(if (chave.isNullOrBlank()) URL_APP else URL_APP + "#" + Uri.encode(chave))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) abrir(intent)
        if (intent.getBooleanExtra("acao", false)) acaoDaNotificacao()
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        ativo = false
        laco.removeCallbacks(tique)
        TreinoService.parar(this)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Android 12 ou anterior")
    override fun onBackPressed() { voltar() }

    /** Voltar: a página fecha o painel / volta a tela; se não houver o que voltar, o app vai para segundo plano. */
    private fun voltar() {
        web.evaluateJavascript("(window.voltarAndroid && window.voltarAndroid()) ? 1 : 0") { r ->
            if (r != "1") moveTaskToBack(true)
        }
    }

    /** Botão da notificação ("Terminei a série" / "Começar a série"). */
    fun acaoDaNotificacao() {
        runOnUiThread { web.evaluateJavascript("window.acaoAndroid && window.acaoAndroid()", null) }
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** O que a página chama (window.Android). Roda fora da linha da tela. */
    inner class Ponte {
        /** Estado do treino (JSON): {ativo, pausado, titulo, texto, desde, acao}. */
        @JavascriptInterface
        fun treino(json: String) {
            val o = JSONObject(json)
            val agora = o.optBoolean("ativo")
            runOnUiThread {
                if (agora) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (agora && !ativo) { ativo = true; laco.postDelayed(tique, 3000) }
                if (!agora) { ativo = false; laco.removeCallbacks(tique) }
            }
            if (agora) TreinoService.atualizar(this@MainActivity, o) else TreinoService.parar(this@MainActivity)
        }

        /** Fim do descanso: vibra e toca o som de notificação (também com a tela apagada). */
        @JavascriptInterface
        fun alerta() {
            val padrao = longArrayOf(0, 400, 150, 400, 150, 400)
            val vib = if (Build.VERSION.SDK_INT >= 31)
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vib.vibrate(VibrationEffect.createWaveform(padrao, -1))
            try {
                RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
            } catch (_: Exception) {}
        }
    }
}
