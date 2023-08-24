# KTAPI Demo

> 本文档主要介绍如何快速跑通 <mark>KTAPI Demo</mark> 示例
## 1. 环境准备

- <mark>最低兼容 iOS 13.0.0 </mark> 
- Xcode 13.0.0 及以上版本。
- iPhone 6 及以上的手机设备(系统需要 iOS 13.0.0 及以上)。

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

- 联系销售给  AppID  开通 K 歌权限 </mark>(如果您没有销售人员的联系方式可通过智能客服联系销售人员 [Agora 支持](https://agora-ticket.agora.io/)

  - 注: 拉取榜单、歌单、歌词等功能是需要开通权限的

- 在项目的 KTVApiDemo/iOS/Example/KTVApiemo 目录下会有一个 KeyCenter.swift 文件，需要在 KeyCenter.swift 里填写需要的声网 App ID 和 App 证书

  ![xxx](https://accktvpic.oss-cn-beijing.aliyuncs.com/pic/github_readme/ktv/img_ktv_keys_ios.png)

  ```texag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0ag-0-1gpap96h0ag-1-1gpap96h0
  AppId：声网 appid
  Certificate：声网 Certificate
  ```
- 项目的第三方库使用 pod 集成，需要在 KTVApiDemo/iOS/Example/KTVApiemo 目录下执行 pod install ,然后再开始体验项目
- 在 KTVApiDemo/iOS/Example/KTVApiemo 目录下，找到 KTVApiDemo.xcworkspace 文件
- 用 Xcode 运行 .xcworkspace 文件 即可开始您的体验

---
