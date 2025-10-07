package com.freedomcoder.hassai.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.freedomcoder.hassai.AssistActivity

class HassAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.d(TAG, "Assistant service ready")
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        startAssistActivity()
    }

    override fun onShow(sessionArgs: Bundle?, flags: Int) {
        super.onShow(sessionArgs, flags)
        startAssistActivity()
    }

    private fun startAssistActivity() {
        val intent = Intent(this, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "HassAssistantService"
    }
}

class HassAssistantSessionService : VoiceInteractionSessionService() {
    override fun onCreateSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onHandleAssist(data: Bundle?) {
                super.onHandleAssist(data)
                val launchIntent = Intent(context, AssistActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(launchIntent)
            }
        }
    }
}
