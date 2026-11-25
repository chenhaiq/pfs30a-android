# 构建与发布

## 推送个人 GitHub

在个人 GitHub 创建空仓库，然后在本项目目录执行：

```sh
git remote add origin git@github.com:YOUR_USERNAME/pfs30a-android.git
git push -u origin main
```

## 签名

当前手机安装的是本机 debug 签名。若要让 GitHub Release 覆盖升级该安装，必须使用原签名。原密钥通常位于本机 ~/.android/debug.keystore，密码和别名为 Android 默认 debug 值。此方式适用于个人侧载。为正式发行创建专用密钥时，原 debug 安装无法直接以新签名覆盖更新，先规划数据迁移。

在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置：

- ANDROID_KEYSTORE_BASE64：签名 keystore 文件的 Base64 内容。
- ANDROID_STORE_PASSWORD：keystore 密码。
- ANDROID_KEY_ALIAS：签名别名。
- ANDROID_KEY_PASSWORD：密钥密码。

密钥应安全备份，保持后续版本使用同一份密钥。不要把密钥提交到仓库。

## 发布版本

修改 app/build.gradle 中的 versionCode 和 versionName，提交后创建与版本名一致的标签：

```sh
git tag v1.3
git push origin v1.3
```

Release 工作流编译签名 APK，并在对应 GitHub Release 中上传 APK 和 SHA256 校验文件。首次推送代码不会自动产生 Release。构建未成功时检查 Actions 日志。

本地开发使用 ./gradlew assembleDebug。CI 会对提交和 PR 执行编译。
