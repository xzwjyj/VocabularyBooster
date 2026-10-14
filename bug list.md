<!-- 已实现 2026-09-19（feat(dictionary) dd17820，FR-18）：随包 ECDICT 全量词典 83.6 万条（44.9 万词 + 38.7 万短语），查词 DB 未命中自动按需导入；精选种子词（含例句）优先 -->
<!-- - 查词收入的词汇表不全，收入所有英语单词以及短语 -->
<!-- 已实现 2026-09-19（feat(tts) c991efb，FR-19）：设置页英/中音色枚举选择 + 系统语音设置直达；自然度上限=设备已装音色 -->
<!-- - 播放中英文语音机械不自然，改成接近自然人声语音播放方案，并在设置里提供不同音色支持 -->
<!-- 已实现 2026-09-19（feat(stats) bd46150，FR-20）：勋章页顶部统计卡（今日学习/复习、累计学习、今日/累计时长）+ 点击进入日/月/年学习词数与时长柱状图 -->
<!-- - 勋章里增加”统计“显示区：里面包含今日学习&复习词数，累计学习词数（不含复习词），今日学习时长，累计学习时长。点击该显示区可以详细显示按日、月、年查看的单词学习量(不含重复的复习单词)及学习时长曲线 -->
<!-- 已实现 2026-09-19（feat(icon) 8d6c71f）：书本+声波自适应矢量图标（含 Android 13+ 主题图标）；装机目视待走查 -->
<!-- - 给app提供一个好看的UI图标，而不是系统默认的应用图标 -->

<!-- 已实现 2026-09-20（feat(learning) FR-21）：学习会话滚动卡片面板（音标 + 释义卡 + 例句卡，数据同源播放选中项）+ 当前播放段卡片高亮/行加粗/词头变色；暂停保持高亮、换词重载；配套 FR-18 音标回填（lookup 幂等补空缺）——vivo 装机实测全通过 -->
<!-- - 在学习会话界面应该显示当前单词的所有相关信息（单词、音标、中英文释义、中英文例句），并对当前正在播放的信息进行高亮显示 -->
<!-- 已实现 2026-09-20（feat(tts) e38c2a2，FR-23 v2 实时合成；v1 预录 5000 词装机听感零差异证伪撤销——覆盖错位且预录无法覆盖长尾/释义/例句任意文本）：端内实时神经 TTS——sherpa-onnx v1.13.8 + Piper VITS medium（en_US lessac / en_GB alan 各 ~63MB；模型单文件 assets 直读，espeak-ng-data 解包至 filesDir），LangRoutedSpeechSynthesizer 按段路由（EN→神经、ZH→系统零变化），口音=模型（无厂商英音包设备也生效，下一英文段生效），WAV 磁盘缓存 100MB LRU，神经不可用 EN 段当场回退系统 TTS 进程内粘滞；APK 196MB→324MB。用户 vivo 人耳验收通过（无问题）。装机四坑全修（diag 文件取证）：① Koin 复合绑定裸 get() 自引用 StackOverflow→按具体类型解析；② espeak-ng-data 传 assets 路径→native exit 拖崩全进程→解包 filesDir；③ 流式回调 JNI 精确签名 invoke([F)Ljava/lang/Integer; 对 D8 脱糖 lambda 不可达→NoSuchMethodError→弃流式改整段 generate；④ vivo mixer 对未填满缓冲/无阻塞写的 STREAM 轨道不启动消费（<缓冲段全静音，audio_flinger Flushed=全部帧 实证）→轨道缓冲缩 16KB+微型段补静音+阻塞写挪 IO 线程；修复后装机一轮 8 段全部实时消费 0 卡死 -->
<!-- - 用piper实现更自然的语音朗读，可以支持英音，不依赖设备厂商语音包 -->
<!-- - 5万以内词优先使用预录音频，5万以外使用 Piper 实时合成或系统 TTS（本批=前 5000 词预录；实时合成与英音 voice 待真机质量验证后立项）——已被 v2 取代：实时合成覆盖任意文本，预录路径废弃 -->

<!-- 已实现 2026-09-20（feat(tts) FR-24，批准 SCR_NEURAL_ZH_TTS + ADR-012 预授权备胎切换）：中文段端内实时神经 TTS。kokoro int8 首选批全程走完（门禁全绿 + 装机坑 #5 修复后正常出声 speakers=103/24kHz/双预热 READY 2349ms/PSS 488MB）但**用户人耳否决**（「还不如上一版」）→ 按 ADR-012 备胎条款切换 **vits-melo-tts-zh_en**（MyShell MeloTTS，MIT；fp32 170.4MB，44.1kHz，zh+en 混合词典，单说话人女声；jieba dict + date/number/phone ruleFsts + new_heteronym ruleFars）。装机坑 #5（kokoro 批发现，对 melo 同样适用）：lexicon 逗号列表读取器在 assetManager 非空时一律走 assets 分支，「absolute path + assetManager NOT set to null」告警后读失败 → native exit(-1) 拖崩全进程（EXIT_SELF status=255）→ ZH 槽整槽 newFromFile，全输入解包 filesDir tts/melo-data/；设备遗留 tts/kokoro-data 启动回收。中文音色 4 sid → 单音色「MeloTTS（神经·女）」（模型单说话人平台限制；FR-19 键复用/未知值回缺省；voice id 进缓存键换模型天然隔离）。采样率按模型（piper 22050/melo 44100）随缓存头往返。路由器 ZH 分支与 EN 同构不变（非 READY/非取消异常→当场回退系统+每语言族进程内粘滞）。assets tts/melo 191.2MB，APK 464.7MB；melo 批门禁：jvmTest 41 类 315/0（ImportPerfTest 按机器级故障先例排除，证据见文末签名条）+ testDebugUnitTest + detekt×2 + checkPlatformBoundaries + assembleDebug 全绿。vivo 二轮人耳验收待执行：中文自然度/英文零变化/首播延迟与缓存/降级模拟/双预热/PSS。若 melo 仍不达：zipvoice-distill-int8 试装 / 云 TTS 立项 / 撤神经部分，三选一呈用户 -->
<!-- - 继续改善中文声音机械问题 -->
<!-- - 新的中文音色还不如上一版，采用其它方案优化中文音色（→ ADR-012 备胎 melo 执行中；2026-09-20 晚）-->

<!-- 机器级故障证据·第五签名（2026-09-20 晚，本文件永不提交，仅本机记录）：FR-24 门禁期 ImportPerfTest 连续 3 次确定性失败，同一 NPE：java.lang.NullPointerException: Cannot invoke "Function0.invoke()" because "onClose" is null at app.cash.sqldelight.driver.jdbc.JdbcDriver.execute(JdbcDriver.kt:140) ← WordBookEntryQueries.insertEntry ← ImportEngine——首条插入即炸，失败用例在三跑间轮换（midImportCancellationRollsBackWithinOneSecond / hundredThousandLinesImportWithinSixtySeconds）。决定性基线复核：git stash 后干净 HEAD e38c2a2（当日早晨 jvmTest 全绿，ADR-011 门禁）同症状复现（BASELINE_EXIT=1，XML 同 NPE），stash pop 恢复——FR-24 工作树零 import/JDBC 改动，无责；当日空闲内存 ~5.6GB（高于历史 ~2GB 崩溃阈值）；该机已集齐五种独立签名：CPython 段错误（ADR-008 同点 6/6）/ V8 fatal / JVM hs_err×8 + Gradle Test Executor 0xC0000005（ADR-007）/ 0xC0000374（ADR-009 首跑）/ 本次 JDBC 驱动内部 NPE。处置：按 ADR-007 先例排除 ImportPerfTest 跑门禁，待重启机器后单跑复核（./gradlew :shared:jvmTest --tests "com.vocabularybooster.ImportPerfTest"）；根治建议 memtest86+ -->
<!-- 机器级故障证据·第七签名（2026-09-25，SCR-ZHNUM 门禁期）：① 全量 jvmTest Test Executor 4s 非零退出（`finished with non-zero exit value 1`，仅写出 1 个 XML 后即死，无失败用例）；② 复跑同命令挂死 20+ 分钟零进展（首个字母序类恰为 ImportPerfTest，杀 daemon 解除）；③ 同日 shared/build/tmp/kotlin-classes 出现「not a regular file」损坏产物（SCR-AUDIOTRIM 附录已记录的 Synology 同步故障模式再现，rm -rf shared/build app/build 重建恢复）。处置：排除 ImportPerfTest 跑门禁（ADR-007 先例），待重启机器后单跑复核；根治建议 memtest86+ 不变 -->
<!-- 已实现 2026-10-14（feat(playback) SCR-BGVOICE，FR-12 v1.33 / FR-25 扩展）：熄屏语音「会了」可用——熄屏播放中说「会了」立即掌握换词。前台服务双类型 mediaPlayback|microphone（Android 11+ while-in-use 豁免）+ FOREGROUND_SERVICE_MICROPHONE 权限 + startForeground 降级守卫（双类型失败回退单类型，续播优先）；删后台门禁 wrapper + 移除 ON_STOP 竞态关麦守护（窗口照常后台听音）；Vosk 诊断 fg= 标记（OEM 后台静默取证：fg=false 窗口 RMS 恒零 = 静默实证，退化纯倒计时不阻断续播）。v1.7.0 / versionCode 8。装机走查 TC-BG-08 全过（2026-10-14：dumpsys services types=0x82 双类型 / 5 熄屏窗口 4 命中「会 了」1 超时、RMS 底噪 40-90 vs 说话 3462-4294 = OEM 未静默 / DB 直查 4 行 SessionWord.MASTERED 与命中时间戳逐一对应，组0 全掌握+scroll，deception PLAYING 续播中） -->
<!-- - 熄屏后播放学习会话说"会了"无反应继续继续继续（定性：FR-25 v1.31 设计行为非 bug——后台门禁纯倒计时；实际使用证伪：口袋场景语音是唯一掌握入口，立项 SCR-BGVOICE 撤销后台禁麦红线，2026-10-14 用户裁决） -->
- 增加功能：第一步在PC端存入英文视频和txt格式的单词列表。第二步在PC端提取该英文视频中所有包含txt中单词的会话或句子，并解析出该会话中单词的具体意思。第三步将相应单词意思写入APK单词库（如果没有的话），并将提取到的包含相应单词的原声会话句子及其视频中的原声语音加入例句库。 第四步询问用户将txt单词列表保存到apk手机应用的哪个生词本中，保存时该生词本中的单词释义只保存视频会话语境中的释义，也只保存截取会话例句作为生词本例句和例句原声。后续我将通过这个方法对单词库和生词本进行长期维护，因此要在readme.md文件中写明后续维护流程。


## 2026-10-10 v8 走查疑似「每会话仅首次按需导入落库」——【已撤案，非 app bug】
- 取证：确定性驱动（%TEMP%/dictdiag.py）同进程 quokka+narwhal 连续导入双成功。
- 真相：走查方法缺陷——详情页无输入框、裸 tap 后 input text 丢失/追加成拼串（实证 quokkanarwhalnarwhal），拼串 miss 是正常行为。
- 10-08「install -r 后首会话失效」同因撤案。
- 教训：走查必须 dump 定位+清空输入框，勿信裸坐标。
- 原始记录（留档）：

- 现象：force-stop 后第一词查词导入正常（落库+UI）；同会话第二词起全部 "No definition found"、DB 零写入；与发起页面无关；force-stop 即恢复一次。
- 复现序列（vivo，2026-10-10）：octodecillion ✓ → septendecillion ✗（重回车仍 ✗）→ force-stop → septendecillion ✓ → undecillion ✗ → force-stop → undecillion ✓ → aardwolf ✗。
- 10-08 的「install -r 后首会话写入失效」是本模式子集（那次只验了重启后第一词）。
- 嫌疑：SeedImporter 事务层状态 / FallbackDictionaryProvider 复合层 / BundledDictionaryProvider 缓存。取证入口：logcat（当时无异常输出）→ 需要带日志复现。
- 处置：装机走查规程加「force-stop 后每词独立验证」；修复待 SCR。
