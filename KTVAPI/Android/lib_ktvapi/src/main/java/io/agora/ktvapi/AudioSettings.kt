package io.agora.ktvapi

import io.agora.rtc2.RtcEngine

enum class SceneType(val value: Int) {
    KTV(0),
    ChatRoom(1)
}

enum class SoundCardType constructor(
    val gainValue: Float,
    val presetValue: Int,
    val gender: Int,
    val effect: Int,
) {
    NanShengCiXing(gainValue = 1.0f, presetValue = 4, gender = 0, effect = 0),
    NvShengCiXing(gainValue = 1.0f, presetValue = 4, gender = 1, effect = 0),
    NanShengTianMei(gainValue = 1.0f, presetValue = 4, gender = 0, effect = 1),
    NvShengTianMei(gainValue = 1.0f, presetValue = 4, gender = 1, effect = 1),
    NanShengChangGe(gainValue = 1.0f, presetValue = 4, gender = 0, effect = 2),
    NvShengChangGe(gainValue = 1.0f, presetValue = 4, gender = 1, effect = 2),
    Close(gainValue = -1.0f, presetValue = -1, gender = -1, effect = -1)
}

class AudioSettings constructor(private val mRtcEngine: RtcEngine) {
    companion object {
        var enabled: Boolean = false
    }

    fun enableVirtualSoundCard(sceneType: SceneType, soundCardType: SoundCardType) {
        enabled = soundCardType != SoundCardType.Close
        setVirtualSoundCardType(sceneType, soundCardType)
        if (sceneType == SceneType.KTV) {
            mRtcEngine.setParameters("{\"che.audio.agc.enable\": ${!enabled}}")
        }
    }

    private fun setVirtualSoundCardType(sceneType: SceneType, soundCardType: SoundCardType) {
        // 延迟大(60ms)建议男声甜美在k歌场景下 映射到唱歌私参
        if (sceneType == SceneType.KTV && soundCardType == SoundCardType.NanShengTianMei) {
            setVirtualSoundCardType(SceneType.ChatRoom, SoundCardType.NanShengChangGe)
            return
        }
        // 延迟大(60ms)建议女生甜美在k歌场景下 映射到唱歌私参
        if (sceneType == SceneType.KTV && soundCardType == SoundCardType.NvShengTianMei) {
            setVirtualSoundCardType(SceneType.ChatRoom, SoundCardType.NvShengChangGe)
            return
        }
        mRtcEngine.setParameters("{\"che.audio.virtual_soundcard\":{\"preset\":${soundCardType.presetValue},\"gain\":${soundCardType.gainValue},\"gender\":${soundCardType.gender},\"effect\":${soundCardType.effect}}}")
    }
}