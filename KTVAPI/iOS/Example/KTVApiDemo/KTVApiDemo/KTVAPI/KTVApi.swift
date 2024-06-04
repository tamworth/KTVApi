//
//  KTVApiDelegate.swift
//  AgoraEntScenarios
//
//  Created by wushengtao on 2023/3/14.
//

import Foundation
import AgoraRtcKit
import AgoraMccExService

///歌曲加载状态
public enum KTVMusicDownloadState {
    case normal
    case progress(Int)
    case success
    case fail(KTVLoadSongFailReason)
}

/**
 * K歌场景类型
 * @param Normal 普通独唱或合唱
 * @param SingBattle 嗨歌抢唱
 * @param Cantata 50人以上大合唱
 */
@objc public enum KTVType: Int  {
    case Normal
    case SingBattle
    case Cantata
}

/// 用户角色
@objc public enum KTVSingRole: Int {
    case soloSinger = 0     //独唱者
    case coSinger           //伴唱
    case leadSinger         //主唱
    case audience           //观众
//    case followSinger       //跟唱
}


/// 歌曲状态
@objc public enum KTVPlayerTrackMode: Int {
    case origin = 0    //原唱
    case acc           //伴奏
}

/// 加载歌曲失败原因
@objc public enum KTVLoadMusicMode: Int {
    case loadNone
    case loadMusicOnly
    case loadLrcOnly
    case loadMusicAndLrc
}

/// 加载歌曲失败原因
@objc public enum KTVLoadSongFailReason: Int {
    case noLyricUrl = 0         //无歌词
    case musicPreloadFail   //歌曲预加载失败
    case cancled // 本次加载取消/停止
}

@objc public enum KTVSwitchRoleState: Int {
    case success = 0
    case fail
}

@objc public enum KTVSwitchRoleFailReason: Int {
    case none = 0
    case joinChannelFail
    case noPermission
}


/// 加入合唱结果状态
@objc public enum KTVJoinChorusState: Int {
    case success = 0    //加入合唱成功
    case fail           //加入合唱失败
}


/// 加入合唱失败原因
@objc public enum KTVJoinChorusFailReason: Int {
    case musicPreloadFail  //歌曲预加载失败
    case musicOpenFail     //歌曲打开失败
    case joinChannelFail   //加入ex频道失败
    case musicPreloadFailAndJoinChannelFail
}

@objc public protocol IMusicLoadStateListener {
    
    
    /// 歌曲进度
    /// - Parameters:
    ///   - songCode: <#songCode description#>
    ///   - percent: 歌曲加载进度 范围： 0-100
    ///   - status: <#status description#>
    ///   - msg: <#msg description#>
    ///   - lyricUrl: <#lyricUrl description#>
    func onMusicLoadProgress(songCode: Int, percent: Int, status: AgoraMusicContentCenterExState, msg: String?, lyricUrl: String?)
    
    /// 歌曲加载成功
    /// - Parameters:
    ///   - songCode: 歌曲code
    ///   - lyricUrl: 歌词远端url
    func onMusicLoadSuccess(songCode: Int, lyricUrl: String)

    
    /// 歌曲加载失败
    /// - Parameters:
    ///   - songCode: 歌曲code
    ///   - lyricUrl: 歌曲远端url
    ///   - reason: 错误原因
    func onMusicLoadFail(songCode: Int, reason: KTVLoadSongFailReason)
}


//public protocol KTVJoinChorusStateListener: NSObjectProtocol {
//
//    /// 加入合唱成功
//    func onJoinChorusSuccess()
//
//    /// 加入合唱失败
//    /// - Parameter reason: 失败原因
//    func onJoinChorusFail(reason: KTVJoinChorusFailReason)
//}

@objc public protocol KTVLrcViewDelegate {
    func onUpdatePitch(pitch: Float)
    func onUpdateProgress(progress: Int)
    func onDownloadLrcData(url: String)
    /// 492KTV歌房使用
    @objc optional func receiveStreamMessage(content: NSDictionary)
}

@objc public protocol KTVApiEventHandlerDelegate {
    
    /// 歌曲播放状态变化
    /// - Parameters:
    ///   - state: <#state description#>
    ///   - error: <#error description#>
    ///   - isLocal: <#isLocal description#>
    func onMusicPlayerStateChanged(state: AgoraMediaPlayerState,
                                   error: AgoraMediaPlayerError,
                                   isLocal: Bool)
    
    
    /// 歌曲得分回调
    /// - Parameter score: <#score description#>
    func onLineScore(songCode: Int, value: AgoraLineScoreData)

    
    /// 角色切换回调
    /// - Parameters:
    ///   - oldRole: <#oldRole description#>
    ///   - newRole: <#newRole description#>
    func onSingerRoleChanged(oldRole: KTVSingRole, newRole: KTVSingRole)
    
    

   func onTokenPrivilegeWillExpire()
        
    /**
         * 合唱频道人声音量提示
         * @param speakers 不同用户音量信息
         * @param totalVolume 总音量
         */
    func onChorusChannelAudioVolumeIndication(
        speakers: [AgoraRtcAudioVolumeInfo],
        totalVolume: Int)
}

@objc open class KTVApiConfig: NSObject{
    var appId: String
    var mcc: AgoraMusicContentCenterEx?
    weak var engine: AgoraRtcEngineKit?
    var channelName: String
    var localUid: Int = 0
    var chorusUid: Int = 0
    var chorusChannelName: String
    var chorusChannelToken: String
    var mpkChannelToken: String
    var maxCacheSize: Int = 10
    var ktvType: KTVType = .Normal
    var isRoomOwner: Bool = false
    @objc public
    init(appId: String,
         mcc: AgoraMusicContentCenterEx,
         engine: AgoraRtcEngineKit,
         channelName: String,
         localUid: Int,
         chorusUid: Int,
         chorusChannelName: String,
         chorusChannelToken: String,
         mpkChannelToken: String,
         maxCacheSize: Int,
         ktvType: KTVType,
         isRoomOwner: Bool
    ) {
        self.appId = appId
        self.mcc = mcc
        self.engine = engine
        self.channelName = channelName
        self.localUid = localUid
        self.chorusUid = chorusUid
        self.chorusChannelName = chorusChannelName
        self.chorusChannelToken = chorusChannelToken
        self.mpkChannelToken = mpkChannelToken
        self.maxCacheSize = maxCacheSize
        self.ktvType = ktvType
        self.isRoomOwner = isRoomOwner
    }
}

/// 歌曲加载配置信息
@objcMembers open class KTVSongConfiguration: NSObject {
    public var songIdentifier: String = ""
    public var autoPlay: Bool = false   //是否加载完成自动播放
    public var mainSingerUid: Int = 0     //主唱uid
    public var mode: KTVLoadMusicMode = .loadMusicAndLrc
}


public typealias LyricCallback = ((String?) -> Void)
public typealias LoadMusicCallback = ((AgoraMusicContentCenterExState, NSInteger) -> Void)
public typealias ScoreCallback = ((AgoraMusicContentCenterExState, NSInteger) -> Void)
public typealias ISwitchRoleStateListener = (KTVSwitchRoleState, KTVSwitchRoleFailReason) -> Void
public typealias MusicChartCallBacks = (String, AgoraMusicContentCenterStatusCode, [AgoraMusicChartInfo]?) -> Void
public typealias MusicResultCallBacks = (String, AgoraMusicContentCenterStatusCode, AgoraMusicCollection) -> Void
public typealias JoinExChannelCallBack = ((Bool, KTVJoinChorusFailReason?)-> Void)

@objc public protocol KTVApiDelegate: NSObjectProtocol {
    
    /// 初始化
    /// - Parameter config: <#config description#>
    init(config: KTVApiConfig)
    
    
    /// 订阅KTVApi事件
    /// - Parameter ktvApiEventHandler: <#ktvApiEventHandler description#>
    func addEventHandler(ktvApiEventHandler: KTVApiEventHandlerDelegate)
    
    
    /// 取消订阅KTVApi事件
    /// - Parameter ktvApiEventHandler: <#ktvApiEventHandler description#>
    func removeEventHandler(ktvApiEventHandler: KTVApiEventHandlerDelegate)
    
    
    /// 清空内部变量/缓存，取消在initWithRtcEngine时的监听，以及取消网络请求等
    func cleanCache()
    
    /**
     * 收到 IKTVApiEventHandler.onTokenPrivilegeWillExpire 回调时需要主动调用方法更新Token
     * @param rtmToken musicContentCenter模块需要的rtm token
     * @param chorusChannelRtcToken 合唱需要的频道rtc token
     */
    func renewToken(
        rtmToken: String,
        chorusChannelRtcToken: String)
    
    /**
     * 获取歌曲榜单
     * Parameter completion: 榜单列表回调
     */
//    func fetchMusicCharts(completion:@escaping MusicChartCallBacks)
    
    /**
    * 根据歌曲榜单类型搜索歌单
    *  Parameters:
    *  musicChartId: 榜单id
    *  page: 榜单的查询页数
    *  pageSize: 查询每页的数据长度
    *  jsonOption: 自定义过滤模式
    *  completion: 歌曲列表回调
    */
//    func searchMusic(musicChartId: Int,
//                     page: Int,
//                     pageSize: Int,
//                     jsonOption: String,
//                     completion:@escaping MusicResultCallBacks)
    
    /**
    * 根据关键字搜索歌曲
    *  Parameters:
    *  keyword: 搜索关键字
    *  page: 榜单的查询页数
    *  pageSize: 查询每页的数据长度
    *  jsonOption: 自定义过滤模式
    *  completion: 歌曲列表回调
    */
//    func searchMusic(keyword: String,
//                     page: Int, pageSize: Int,
//                     jsonOption: String,
//                     completion: @escaping MusicResultCallBacks)
            
    
    
    /// 加载歌曲
    /// - Parameters:
    ///   - config: <#config description#>
    ///   - onMusicLoadStateListener: <#onMusicLoadStateListener description#>
    func loadMusic(songCode: Int, config: KTVSongConfiguration, onMusicLoadStateListener: IMusicLoadStateListener)
    
    /// 通过url加载歌曲
    /// - Parameters:
    ///   - config: <#config description#>
    ///   - onMusicLoadStateListener: <#onMusicLoadStateListener description#>
    func loadMusic(config: KTVSongConfiguration, url: String)
    
    
    func startScore(songCode: Int, callback: @escaping ScoreCallback)
    
    /// 切换角色
    /// - Parameters:
    ///   - newRole: <#newRole description#>
    ///   - token: <#token description#>
    ///   - onSwitchRoleState: <#onSwitchRoleState description#>
    func switchSingerRole(newRole: KTVSingRole, onSwitchRoleState:@escaping ISwitchRoleStateListener)
    
    
    /// 播放
    /// - Parameter startPos: <#startPos description#>
    func startSing(songCode: Int, startPos: Int)
    
    /**
     * 播放歌曲
     * @param url 歌曲地址
     * @param startPos 开始播放的位置
     * 对于主唱：
     * 如果loadMusic时你选择了autoPlay = true 则不需要主动调用startSing
     * 如果loadMusic时你选择了autoPlay = false 则需要在loadMusic成功后调用startSing
     */
    func startSing(url: String, startPos: Int)
    
    /// 恢复播放
    func resumeSing()
    
    
    /// 暂停播放
    func pauseSing()
    
    
    /// 调整进度
    /// - Parameter time: 进度，单位ms
    func seekSing(time: Int)
    
    /**
     * 设置当前音频播放delay， 适用于音频自采集的情况
     * @param audioPlayoutDelay 音频帧处理和播放的时间差
     */
    func setAudioPlayoutDelay(audioPlayoutDelay: Int)
    
    /**
         * 设置演唱的分数，推荐使用歌词组建回调的单句得分
         * @param score 演唱分数
         */
    func setSingingScore(score: Int)

    
    /// 设置歌词组件，在任意时机设置都可以生效
    /// - Parameter view: <#view description#>
    func setLrcView(view: KTVLrcViewDelegate)
    
    /**
     * 设置歌词进度
     * @param data 观众收到的进度streamMessage
     */
    func setLrcProgress(data: Data?)
    
    
    /// 设置当前mic开关状态目前关麦调用
    /// 目前关麦调用 adjustRecordSignalVolume(0) 后 onAudioVolumeIndication 仍然会执行， ktvApi需要增加一个变量判断当前是否关麦， 如果关麦把设置给歌词组件的pitch改为0
    /// - Parameter isOnMicOpen: <#isOnMicOpen description#>
    func setMicStatus(isOnMicOpen: Bool)
    
    /// 获取mpk实例
    /// - Returns: <#description#>
    func getMediaPlayer() -> AgoraMusicPlayerProtocolEx?
    
    /// 获取MCC实例
    /// - Returns: <#description#>
    func getMusicContentCenter() -> AgoraMusicContentCenterEx?
    
    /**
     创建dataStreamID
     */
    func renewInnerDataStreamId()
}
