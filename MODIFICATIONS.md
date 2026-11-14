# Sime 后续修改补丁（2026-11-14）

## 修改基准

严格以此前确认成功的 `SimeApp-0.16.0-fixed-modified.zip` 为基础，并先叠加上一轮成功的 `SimeApp-0.16.0-keyboard-followup-patch.zip`，再进行本次修改。上一轮修改均予以保留。

## 本次修改

1. `CandidatesBar.java`：九宫格中文模式下，只要拼音组合状态非空，就隐藏候选栏最右侧的展开/隐藏按钮，候选栏恢复空闲状态后自动恢复显示；保留符号选择器及展开候选页原有隐藏规则。
2. `CandidateMenu.java`：候选词长按菜单改为可获取焦点的 PopupWindow，并提供当前菜单统一关闭入口，使 Android 返回键可以关闭菜单而不触发输入法其他返回动作。
3. `SimeService.java`：优先截获返回键并关闭当前候选菜单；没有菜单时保持系统原有返回键处理。
4. `SettingsKeyboardView.java`：设置根菜单中“符号”与“联想”两个选项交换位置。
5. `SymbolKeyboardView.java`：符号面板底部返回键宽度扩大为两个分类键位（104dp），返回箭头字号同步增大并保持居中；分类键区域随之右移。

## 未修改

未修改引擎、解码器、词库、个人字典、Gradle/SDK/NDK 配置及其他键盘行为。未构建 APK，未进行 Pixel 3 实机测试。
