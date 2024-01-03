package io.agora.ktvdemo.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import io.agora.ktvapi.KTVSingRole
import io.agora.ktvdemo.rtc.IChannelEventListener
import io.agora.ktvdemo.R
import io.agora.ktvdemo.rtc.RtcEngineController
import io.agora.ktvdemo.databinding.FragmentMainBinding
import io.agora.ktvdemo.utils.KeyCenter
import io.agora.ktvdemo.utils.TokenGenerator
import kotlin.random.Random

/*
 * 体验前配置页面
 */
class MainFragment : BaseFragment<FragmentMainBinding>() {

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMainBinding {
        return FragmentMainBinding.inflate(inflater)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.apply {
            resetRoleView()
            setRoleView()

            // 频道名输入框，开始体验前需要输入一个频道名
            etChannelId.doAfterTextChanged {
                KeyCenter.channelId = it?.trim().toString()
            }

            // 初始角色选择主唱，一个体验频道只能有一个主唱
            btnLeadSinger.setOnClickListener {
                resetRoleView()
                KeyCenter.role = KTVSingRole.LeadSinger
                setRoleView()
            }

            // 初始角色选择观众，一个体验频道可以有多个观众
            btnAudience.setOnClickListener {
                resetRoleView()
                KeyCenter.role = KTVSingRole.Audience
                KeyCenter.localUid = Random(System.currentTimeMillis()).nextInt(100000) + 1000000
                setRoleView()
            }

            // 选择加载歌曲的类型， MCC 声网歌曲中心或者本地歌曲
            groupSongType.setOnCheckedChangeListener { _, checkedId -> KeyCenter.isMcc = checkedId == R.id.rbtMccSong }

            // 开始体验按钮
            btnStartChorus.setOnClickListener {
                if (KeyCenter.channelId.isEmpty()){
                    toast(getString(R.string.app_input_channel_name))
                    return@setOnClickListener
                }
                RtcEngineController.eventListener = IChannelEventListener()

                // 这里一共获取了三个 Token
                // 1、加入主频道使用的 Rtc Token
                // 2、如果要使用 MCC 模块获取歌单、下载歌曲，需要 RTM Token 进行鉴权，如果您有自己的歌单就不需要获取该 token
                // 3、合唱需要用到的合唱子频道 token，如果您只需要独唱就不需要获取该 token
                TokenGenerator.generateTokens(KeyCenter.channelId,
                    KeyCenter.localUid.toString(),
                    TokenGenerator.TokenGeneratorType.token006,
                    arrayOf(
                        TokenGenerator.AgoraTokenType.rtc,
                        TokenGenerator.AgoraTokenType.rtm
                    ),
                    success = { ret ->
                        val rtcToken = ret[TokenGenerator.AgoraTokenType.rtc] ?: ""
                        val rtmToken = ret[TokenGenerator.AgoraTokenType.rtm] ?: ""
                        TokenGenerator.generateToken("${KeyCenter.channelId}_ex", KeyCenter.localUid.toString(),
                            TokenGenerator.TokenGeneratorType.token007, TokenGenerator.AgoraTokenType.rtc,
                            success = { chorusToken ->
                                RtcEngineController.rtcToken = rtcToken
                                RtcEngineController.rtmToken = rtmToken
                                RtcEngineController.chorusChannelRtcToken = chorusToken
                                findNavController().navigate(R.id.action_mainFragment_to_livingFragment)
                            },
                            failure = {
                                toast("获取 token 异常")
                            }
                        )
                    },
                    failure = {
                        toast("获取 token 异常")
                    }
                )
            }
        }
    }

    private fun resetRoleView() {
        binding?.apply {
            btnLeadSinger.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.lighter_gray, null))
            btnAudience.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.lighter_gray, null))
        }
    }

    private fun setRoleView() {
        binding?.apply {
            if (KeyCenter.role == KTVSingRole.LeadSinger) {
                btnLeadSinger.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.darker_gray, null))
            } else {
                btnAudience.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.darker_gray, null))
            }
        }
    }
}