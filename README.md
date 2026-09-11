# LiteKeeping for ezBookkeeping

LiteKeeping for ezBookkeeping 是一个连接 ezBookkeeping 官方 Docker 版服务的 Android 轻量记账应用，并提供 Android 桌面小组件，让记账可以顺手完成。

## 使用前提

本项目不是独立的记账服务器。使用前必须先：

1. 部署一个可以从手机访问的 ezBookKeeping 官方 Docker 版实例。
2. 在服务器中注册好用户账户，并确认手机能够通过 HTTPS 访问服务器。
3. 在 LiteKeeping 中填写服务器地址，使用已注册的账户登录。

ezBookKeeping 官方文档：[https://ezbookkeeping.mayswind.net/zh_Hans/](https://ezbookkeeping.mayswind.net/zh_Hans/)

## 项目亮点

- 支持创建 Android 桌面小组件，直接从桌面进入记支出、记收入和记转账。
- 使用服务器账户、二级分类和标签，提交数据直接写入 ezBookKeeping。
- 记账时获取当前位置，确认后按服务器 `geoLocation` 格式上传。
- 可修改账单日期和时间，转账使用一个金额输入并同时提交转出、转入金额。
- 应用内嵌移动端网页，复用应用当前登录账户。
- 主界面交易记录直接从云端接口读取，不在本机保存提交流水。
- Bearer Token 和账户缓存使用 Android Keystore 加密保存，密码不会落盘。

## 构建

项目使用 JDK 17、Android SDK 37、Android Gradle Plugin 9.4 和 Gradle 9.6。

```powershell
$env:JAVA_HOME = 'D:\MyFiles\Softwares\Android Dev\jdk-17'
$env:ANDROID_SDK_ROOT = 'D:\MyFiles\Softwares\Android Dev\android-sdk'
$env:GRADLE_USER_HOME = 'D:\MyFiles\Softwares\Android Dev\gradle-home'
.\gradlew.bat assembleDebug
```

构建完成后，Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。安装后，在系统桌面的小组件选择器中添加“轻记账”。

## API 说明

服务器接口、登录方式和交易请求字段见 [API_COMMUNICATION.md](API_COMMUNICATION.md)。项目只保存加密后的登录令牌，不要把密码或个人令牌提交到仓库。

## 版本

当前版本：`1.1.0`。

## 更新日志

### 1.1.0

- 小组件改为显示今日总支出，并支持切换到本月总支出；支出统计不再用收入抵扣。
- 增加小组件手动刷新按钮，刷新时显示加载动画，完成后可靠恢复金额显示。
- 优化小组件刷新和网页操作后的云端数据同步，修复刷新后金额不显示、数据不及时更新的问题。
- 小组件快捷记账改为桌面浮窗，不再跳转到主界面；取消或提交后自动关闭遮罩和浮窗，减少重复后台窗口。
- 优化首次同步、弱网重连和应用被系统回收后的重新连接，并增加主页面和浮窗加载提示。
- 修复当前位置权限申请、位置状态判断及位置获取成功后仍错误弹出确认框的问题。
- 记账界面支持修改账单时间、标签、转账和经纬度上传；转账只需输入一次金额。
- 修复账户大类/子账户、分类大类/子分类及云端交易记录的显示，未填写备注时显示“无备注”。
- 内嵌网页使用移动端交易列表，复用应用登录状态；退出登录时同步清理网页会话，避免再次打开仍保持登录。
- 优化下拉菜单圆角、箭头位置、顶部留白、按钮布局及 ezBookKeeping 品牌文字。
