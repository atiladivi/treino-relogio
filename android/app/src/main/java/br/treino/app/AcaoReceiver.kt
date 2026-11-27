package br.treino.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botão da notificação: faz a ação da série na página (sem abrir o app, se ele já estiver aberto). */
class AcaoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = MainActivity.instancia
        if (a != null) a.acaoDaNotificacao()
        else ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("acao", true))
    }
}
