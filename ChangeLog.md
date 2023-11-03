## v3.3.2

##### 1、修正 iOS KTVAPI 伴唱默认未播放伴奏问题



## v3.3.1

##### 1、修正 Android KTVAPI 在4.1.1.17版本 RTC SDK 上加入合唱子频道的回调重复触发问题

##### 2、修正 renewToken 后未更新合唱子频道 token 导致的无法加入合唱子频道的问题



## v3.3.0

##### 1、KTVApiConfig

新增参数musicType: 音乐类型，默认为mcc曲库

##### 2、KTVMusicType

新增类型

~~~
/**
	* KTV歌曲类型
	* @param SONG_CODE mcc版权歌单songCode
	* @param SONG_URL 本地歌曲地址url
  */
  enum class KTVMusicType(val value: Int) {
  	SONG_CODE(0),
  	SONG_URL(1)
  }
~~~

##### 3、enableProfessionalStreamerMode

新增接口, 专业模式开关, 开启专业模式后, 场景化api会针对耳机/外放采用不同的音频配置