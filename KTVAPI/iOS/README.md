# K 歌场景化 API 示例 demo

> 本文档主要介绍如何快速跑通 K 歌场景化 API 示例工程，支持加载、播放声网内容中心版权音乐和本地音乐文件。
>
> **Demo 效果:**
>
> <img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo3.jpg" width="300" height="640"><img src="https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/ktvapi_demo4.jpg" width="300" height="640">
---

## 1. 环境准备

- <mark>最低兼容 iOS 13.0.0 </mark> 
- Xcode 13.0.0 及以上版本
- iPhone 6 及以上的手机设备(系统需要 iOS 13.0.0 及以上)

---

## 2. 运行示例

- 获取声网 App ID -------- [声网Agora - 文档中心 - 如何获取 App ID](https://docs.agora.io/cn/Agora%20Platform/get_appid_token?platform=All%20Platforms#%E8%8E%B7%E5%8F%96-app-id)
  
  > - 点击创建应用
  >
  >   ![xxx](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/create_app_1.jpg)
  >
  > - 选择你要创建的应用类型
  >
  >   ![xxx](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/create_app_2.jpg)
  >
  > - 得到 App ID 与 App 证书
  >
  >   ![xxx](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/get_app_id.jpg)

- 获取 App 证书 ----- [声网Agora - 文档中心 - 获取 App 证书](https://docs.agora.io/cn/Agora%20Platform/get_appid_token?platform=All%20Platforms#%E8%8E%B7%E5%8F%96-app-%E8%AF%81%E4%B9%A6)

- **联系销售给 AppID 开通 K 歌权限(如果您没有销售人员的联系方式可通过智能客服联系销售人员 [Agora 支持](https://agora-ticket.agora.io/))**

    ```json
    注: 拉取声网版权榜单、歌单、歌曲、歌词等功能是需要开通权限的, 仅体验本地音乐文件模式可以不用开通
    ```

- 在项目的 KTVApiDemo/iOS/Example/KTVApiemo 目录下会有一个 KeyCenter.swift 文件，需要在 KeyCenter.swift 里填写需要的声网 App ID 和 App 证书

  ![xxx](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/img_ktv_keys_ios.png)

  ```texag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0
  AppId：声网 appid
  Certificate：声网 Certificate
  ```
- 项目的第三方库使用 pod 集成, 需要在 KTVApiDemo/iOS/Example/KTVApiemo 目录下执行 pod install, 然后再开始体验项目
- 在 KTVApiDemo/iOS/Example/KTVApiemo 目录下，找到 KTVApiDemo.xcworkspace 文件
- 用 Xcode 运行 .xcworkspace 文件即可开始您的体验

---

## 3. 如何集成场景化 API 实现 K 歌场景
详见[**官网文档**](https://doc.shengwang.cn/doc/online-ktv/ios/implementation/ktv-scenario/get-music)

### 3. 集成遇到困难，该如何联系声网获取协助

> 方案1：如果您已经在使用声网服务或者在对接中，可以直接联系对接的销售或服务
>
> 方案2：发送邮件给 [support@agora.io](mailto:support@agora.io) 咨询
>
> 方案3：扫码加入我们的微信交流群提问
>
> <img src="https://download.agora.io/demo/release/SDHY_QA.jpg" width="360" height="360">
---
