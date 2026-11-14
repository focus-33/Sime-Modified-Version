# Sime 后续键盘修改（2026-11-14）

修改基准：用户本轮指定的 `SimeApp-0.16.0-fixed-modified.zip`（即上一轮成功修改的源码）。

本补丁仅包含 5 个修改过的 Java 文件，按目录结构覆盖到 GitHub 仓库即可。

## 修改内容

1. `QwertyKeyboardView.java`：仅在输入模式真正发生变化时重置英文上档状态。原先 `InputView.pushSnapshotIntoKeyboard()` 会在每次输入状态更新时再次调用 `setMode(ENGLISH)`，导致上档状态被清除；现在连续输入大写字母时保持上档，用户再次点击上档键才关闭。
2. `InputKernel.java`：九宫格 1 键常用符号表中的半角 `~` 改为全角中文 `～`，`、` 改为 `——`。
3. `CandidatesBar.java`：常用符号候选显示期间隐藏候选栏最右侧的 `∨` 展开/收起控件，避免遮挡符号；退出符号候选状态后自动恢复。
4. `SettingsKeyboardView.java`：设置主菜单增加“符号”入口。
5. `InputView.java`：将设置中的“符号”入口连接到现有 `KeyboardMode.SYMBOL` 符号键盘。符号键盘原有返回键可返回进入设置前的输入模式。

## 未修改

未修改输入引擎、词库、个人字典、键盘布局、Gradle/SDK/NDK 配置及其他功能；未构建 APK。

## 验证限制

已核对修改位置和代码引用。尝试运行 `testDebugUnitTest` 时，项目缺少 `gradle/wrapper/gradle-wrapper.jar`，Gradle Wrapper 无法启动，因此本次没有完成自动化测试或 APK 构建。需要在 GitHub Actions 环境构建后进行实机验证。
