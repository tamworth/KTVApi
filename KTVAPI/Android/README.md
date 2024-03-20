# K 歌场景化 API 示例 demo

> 本文档主要介绍如何快速跑通 K 歌场景化 API 示例工程，本 demo 支持普通合唱、大合唱两种模式, 包含加载、播放声网内容中心版权音乐和本地音乐文件等功能
>
> **Demo 效果:**
>
> <img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo3.jpg" width="300" height="640"><img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo4.jpg" width="300" height="640">
---

## 1. 环境准备

- <mark>最低兼容 Android 5.0</mark>（SDK API Level 21）
- Android Studio 3.5及以上版本
- Android 5.0 及以上的手机设备

---

## 2. 运行示例

- 2.1 进入声网控制台获取 APP ID 和 APP 证书 [控制台入口](https://console.shengwang.cn/overview)

  - 点击创建项目

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_1.jpg)

  - 选择项目基础配置, 鉴权机制需要选择**安全模式**

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_2.jpg)

  - 拿到项目 APP ID 与 APP 证书

    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_3.jpg)
    
  - **Restful API 服务配置（大合唱）**
    ```json
    注: 体验大合唱模式需要填写 Restful API 相关信息
    ```
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_4.jpg)
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_5.jpg)
    ![图片](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ent-full/sdhy_6.jpg)

  - **联系声网技术支持给 APP ID 开通 K 歌歌单权限和云端转码权限([声网支持](https://ticket.shengwang.cn/form?type_id=&sdk_product=&sdk_platform=&sdk_version=&current=0&project_id=&call_id=&channel_name=))**

    ```json
    注: 拉取声网版权榜单、歌单、歌曲、歌词等功能是需要开通歌单权限的, 仅体验本地音乐文件模式可以不用开通
        体验大合唱模式需要开通云端转码权限, 仅体验普通合唱可以不用开通
    ```

- 2.2 在项目的 [**gradle.properties**](gradle.properties) 里填写需要的声网 App ID 和 App 证书、RESTFUL KEY 和 SECRET

  ```
  # RTM RTC SDK key Config
  AGORA_APP_ID：声网 APP ID
  AGORA_APP_CERTIFICATE：声网 APP 证书
  RESTFUL_API_KEY：声网RESTful API key
  RESTFUL_API_SECRET：声网RESTful API secret
  ```
- 2.3 用 Android Studio 运行项目即可开始您的体验

---

## 3. 如何集成场景化 API 实现 K 歌场景
详见[**官网文档**](https://doc.shengwang.cn/doc/online-ktv/android/implementation/ktv-scenario/get-music)

## 4. FAQ
- 集成遇到困难，该如何联系声网获取协助
  - 方案1：可以从智能客服获取帮助或联系技术支持人员 [声网支持](https://ticket.shengwang.cn/form?type_id=&sdk_product=&sdk_platform=&sdk_version=&current=0&project_id=&call_id=&channel_name=)
  - 方案2：加入微信群提问
  
    ![](https://download.agora.io/demo/release/SDHY_QA.jpg)
