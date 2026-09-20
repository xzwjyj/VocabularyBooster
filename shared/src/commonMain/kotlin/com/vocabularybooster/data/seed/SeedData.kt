package com.vocabularybooster.data.seed

/**
 * 本地种子词库（FR-16 v1 唯一数据源；TEST_PLAN §9 fixtures 同源）。
 * 内容约定：
 * - sourceType=TTS：为本种子撰写的例句，音频以 TTS 朗读（FR-3 兜底）；
 * - sourceType=LICENSED_OTHER：公版（public domain，1900 年前出版）名著/KJV 原句，
 *   licenseNote 如实标注公版状态，译文为本项目参考译文；
 * - audioUri 采用平台中立逻辑 scheme `res://<资源名>`（随包资产）；平台 actual 负责解析
 *   （Android → android.resource://<packageName>/raw/<资源名>；iOS → bundle resource），
 *   commonMain 不携带平台专属 URI（架构铁律 1，checkPlatformBoundaries 门禁）；
 * - 未接入任何受版权保护的影视/演讲/有声书音频（NFR-5，Phase 0 约束）。
 */
public const val SEED_DICTIONARY_JSON: String = """
{
  "version": 1,
  "words": [
    {
      "text": "abandon", "ipaAm": "/əˈbændən/", "ipaBr": "/əˈbændən/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to leave somebody or something completely, with no intention of returning",
          "meaningCN": "抛弃；遗弃；离弃",
          "examples": [
            { "sentence": "They had to abandon the car in the deep snow.", "chineseTranslation": "他们不得不把车丢弃在深雪里。", "exampleOrder": 0 },
            { "sentence": "The village was abandoned after the flood.", "chineseTranslation": "洪水过后，村庄被废弃了。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to stop doing or supporting something before it is finished",
          "meaningCN": "中止；放弃（计划、活动）",
          "examples": [
            { "sentence": "They abandoned the search when night fell.", "chineseTranslation": "夜幕降临时，他们放弃了搜寻。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "absorb", "ipaAm": "/əbˈzɔːrb/", "ipaBr": "/əbˈzɔːb/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to take in a liquid, gas, or other substance from a surface or space",
          "meaningCN": "吸收（液体、气体等）",
          "examples": [
            { "sentence": "Plants absorb water through their roots.", "chineseTranslation": "植物通过根部吸收水分。", "exampleOrder": 0 },
            { "sentence": "The towel absorbed the spilled coffee quickly.", "chineseTranslation": "毛巾很快吸干了洒出的咖啡。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to take in information, ideas, or knowledge and understand them",
          "meaningCN": "理解；掌握（信息、知识）",
          "examples": [
            { "sentence": "It takes time to absorb so much new information.", "chineseTranslation": "吸收这么多新信息需要时间。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "accompany", "ipaAm": "/əˈkʌmpəni/", "ipaBr": "/əˈkʌmpəni/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to go somewhere with somebody, especially to look after them",
          "meaningCN": "陪伴；陪同",
          "examples": [
            { "sentence": "Children under twelve must be accompanied by an adult.", "chineseTranslation": "十二岁以下儿童必须由成人陪同。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "acknowledge", "ipaAm": "/əkˈnɑːlɪdʒ/", "ipaBr": "/əkˈnɒlɪdʒ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to accept that something is true or admit that it exists",
          "meaningCN": "承认（事实、存在）",
          "examples": [
            { "sentence": "He refused to acknowledge his mistake.", "chineseTranslation": "他拒绝承认自己的错误。", "exampleOrder": 0 },
            { "sentence": "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
              "chineseTranslation": "这是一条举世公认的真理：凡拥有丰厚财产的单身男子，总想娶位太太。",
              "sourceType": "LICENSED_OTHER", "sourceRef": "Pride and Prejudice (1813), opening line", "licenseNote": "Public domain（1813 年出版，版权已过期）", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to publicly express that you recognize somebody's achievement or authority",
          "meaningCN": "（公开）认可；致谢",
          "examples": [
            { "sentence": "The author acknowledged her editor in the preface.", "chineseTranslation": "作者在前言中向编辑致谢。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "ambitious", "ipaAm": "/æmˈbɪʃəs/", "ipaBr": "/æmˈbɪʃəs/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "having a strong desire to succeed or achieve great things",
          "meaningCN": "有雄心的；有抱负的",
          "examples": [
            { "sentence": "She is ambitious and hopes to run her own company one day.", "chineseTranslation": "她雄心勃勃，希望有一天能经营自己的公司。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "analyze", "ipaAm": "/ˈænəlaɪz/", "ipaBr": "/ˈænəlaɪz/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to examine something carefully in order to understand it or find out more about it",
          "meaningCN": "分析；剖析",
          "examples": [
            { "sentence": "Scientists analyzed the samples in the laboratory.", "chineseTranslation": "科学家们在实验室里分析了这些样本。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "apply", "ipaAm": "/əˈplaɪ/", "ipaBr": "/əˈplaɪ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to make a formal request for a job, position, or admission",
          "meaningCN": "申请；请求",
          "examples": [
            { "sentence": "She decided to apply for the scholarship before the deadline.", "chineseTranslation": "她决定在截止日期前申请那笔奖学金。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to use something or make it work in a particular situation",
          "meaningCN": "运用；应用",
          "examples": [
            { "sentence": "You can apply these principles to almost any design problem.", "chineseTranslation": "你可以把这些原则运用到几乎任何设计问题上。", "exampleOrder": 0 },
            { "sentence": "The new rule applies to all employees.", "chineseTranslation": "新规定适用于所有员工。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 3,
          "meaningEN": "to put or spread a substance onto a surface",
          "meaningCN": "涂；敷；施加（于表面）",
          "examples": [
            { "sentence": "Apply the cream to the affected area twice a day.", "chineseTranslation": "每日两次将药膏涂于患处。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "assess", "ipaAm": "/əˈses/", "ipaBr": "/əˈses/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to judge the quality, amount, or value of something after careful consideration",
          "meaningCN": "评估；评定",
          "examples": [
            { "sentence": "Teachers assessed each student's progress at the end of the term.", "chineseTranslation": "老师们在学期末评估了每个学生的进步情况。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "boost", "ipaAm": "/buːst/", "ipaBr": "/buːst/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to increase or improve something",
          "meaningCN": "提高；使增长",
          "examples": [
            { "sentence": "The marketing campaign boosted sales by twenty percent.", "chineseTranslation": "这场营销活动使销售额提高了两成。",
              "audioUri": "res://vb_placeholder_audio", "audioDurationMs": 4000,
              "sourceType": "LICENSED_OTHER", "sourceRef": "app/src/main/res/raw/vb_placeholder_audio.wav",
              "licenseNote": "项目自生成占位音频（440Hz 正弦音，非语音，无第三方权利）；Phase 4 文件音频通道验证用，后续阶段替换为真实例句音频", "exampleOrder": 0 },
            { "sentence": "Good sleep can boost your immune system.", "chineseTranslation": "良好的睡眠能增强你的免疫系统。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to help or encourage somebody to be more successful",
          "meaningCN": "帮助；激励；促进",
          "examples": [
            { "sentence": "Her praise boosted his confidence before the exam.", "chineseTranslation": "考试前，她的夸奖增强了他的信心。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "an increase or improvement; an act of encouragement",
          "meaningCN": "增长；激励；推动",
          "examples": [
            { "sentence": "The win gave the team a much-needed boost.", "chineseTranslation": "这场胜利给了球队急需的鼓舞。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "boundary", "ipaAm": "/ˈbaʊndri/", "ipaBr": "/ˈbaʊndri/",
      "definitions": [
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "a real or imagined line that marks the edge or limit of something",
          "meaningCN": "边界；界限",
          "examples": [
            { "sentence": "The river marks the boundary between the two provinces.", "chineseTranslation": "这条河是两省的分界线。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "brief", "ipaAm": "/briːf/", "ipaBr": "/briːf/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "lasting or taking only a short time",
          "meaningCN": "短暂的；短时间的",
          "examples": [
            { "sentence": "The meeting was brief but productive.", "chineseTranslation": "会议很短，但很有成效。", "exampleOrder": 0 },
            { "sentence": "After a brief pause, she continued her speech.", "chineseTranslation": "短暂停顿之后，她继续演讲。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 2,
          "meaningEN": "using few words; concise",
          "meaningCN": "简短的；简洁的",
          "examples": [
            { "sentence": "Please write a brief summary of the report.", "chineseTranslation": "请写一份简短的报告摘要。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "a short set of instructions or summary of facts about a task",
          "meaningCN": "任务简报；指示",
          "examples": [
            { "sentence": "The designer received a clear brief from the client.", "chineseTranslation": "设计师收到了客户明确的任务简报。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "capture", "ipaAm": "/ˈkæptʃər/", "ipaBr": "/ˈkæptʃə/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to catch a person or animal and keep them as a prisoner",
          "meaningCN": "俘获；捕获",
          "examples": [
            { "sentence": "The soldiers captured the enemy officer alive.", "chineseTranslation": "士兵们活捉了敌军军官。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to attract and hold somebody's attention or interest",
          "meaningCN": "吸引（注意力）；赢得（兴趣）",
          "examples": [
            { "sentence": "The novel captures the atmosphere of the old city perfectly.", "chineseTranslation": "这部小说完美地捕捉到了老城的氛围。", "exampleOrder": 0 },
            { "sentence": "Her smile captured the hearts of the audience.", "chineseTranslation": "她的微笑赢得了观众的心。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "the act of capturing somebody or something",
          "meaningCN": "捕获；夺取",
          "examples": [
            { "sentence": "The capture of the city ended the war.", "chineseTranslation": "攻占这座城市结束了战争。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "cease", "ipaAm": "/siːs/", "ipaBr": "/siːs/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to stop happening or to stop doing something",
          "meaningCN": "停止；终止",
          "examples": [
            { "sentence": "The company ceased production at the old factory.", "chineseTranslation": "公司停止了老工厂的生产。", "exampleOrder": 0 },
            { "sentence": "Silly things do cease to be silly if they are done by sensible people in an impudent way.",
              "chineseTranslation": "愚蠢的事，若由明智的人肆无忌惮地去做，便不再是愚蠢的了。",
              "sourceType": "LICENSED_OTHER", "sourceRef": "Emma (1815), Jane Austen", "licenseNote": "Public domain（1815 年出版，版权已过期）", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "compel", "ipaAm": "/kəmˈpel/", "ipaBr": "/kəmˈpel/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to force somebody to do something",
          "meaningCN": "强迫；迫使",
          "examples": [
            { "sentence": "The law compels employers to provide safe workplaces.", "chineseTranslation": "法律强制雇主提供安全的工作场所。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "compile", "ipaAm": "/kəmˈpaɪl/", "ipaBr": "/kəmˈpaɪl/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to collect information from different places and arrange it in a book, report, or list",
          "meaningCN": "编纂；汇编",
          "examples": [
            { "sentence": "The team spent two years compiling the dictionary.", "chineseTranslation": "团队花了两年时间编纂这本词典。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "comprehensive", "ipaAm": "/ˌkɑːmprɪˈhensɪv/", "ipaBr": "/ˌkɒmprɪˈhensɪv/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "including all or nearly all elements or aspects of something",
          "meaningCN": "全面的；综合的",
          "examples": [
            { "sentence": "The report gives a comprehensive review of the industry.", "chineseTranslation": "这份报告对该行业作了全面的回顾。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "constrain", "ipaAm": "/kənˈstreɪn/", "ipaBr": "/kənˈstreɪn/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to limit or restrict somebody or something",
          "meaningCN": "限制；约束",
          "examples": [
            { "sentence": "Budget cuts constrained the scope of the research.", "chineseTranslation": "预算削减限制了研究的范围。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "convey", "ipaAm": "/kənˈveɪ/", "ipaBr": "/kənˈveɪ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to transport or carry something from one place to another",
          "meaningCN": "运送；输送",
          "examples": [
            { "sentence": "Pipelines convey natural gas across the country.", "chineseTranslation": "管道将天然气输送到全国各地。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to make ideas or feelings known to somebody",
          "meaningCN": "传达，表达（思想、感情）",
          "examples": [
            { "sentence": "Words cannot convey how grateful I am.", "chineseTranslation": "言语无法表达我的感激之情。", "exampleOrder": 0 },
            { "sentence": "The painting conveys a deep sense of loss.", "chineseTranslation": "这幅画传达出一种深深的失落感。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "crucial", "ipaAm": "/ˈkruːʃl/", "ipaBr": "/ˈkruːʃl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "extremely important because it affects the outcome of something",
          "meaningCN": "至关重要的；决定性的",
          "examples": [
            { "sentence": "This decision is crucial to the future of the company.", "chineseTranslation": "这个决定对公司的未来至关重要。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "deliberate", "ipaAm": "/dɪˈlɪbərət/", "ipaBr": "/dɪˈlɪbərət/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "done on purpose rather than by accident",
          "meaningCN": "故意的；蓄意的",
          "examples": [
            { "sentence": "It was a deliberate attempt to mislead the public.", "chineseTranslation": "那是蓄意误导公众的行为。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to think carefully about something before making a decision",
          "meaningCN": "慎重考虑；仔细讨论",
          "examples": [
            { "sentence": "The jury deliberated for three days before reaching a verdict.", "chineseTranslation": "陪审团仔细讨论了三天才作出裁决。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "demonstrate", "ipaAm": "/ˈdemənstreɪt/", "ipaBr": "/ˈdemənstreɪt/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to show something clearly by giving evidence or proof",
          "meaningCN": "证明；论证；表明",
          "examples": [
            { "sentence": "The study demonstrates the link between diet and health.", "chineseTranslation": "这项研究证明了饮食与健康之间的联系。", "exampleOrder": 0 },
            { "sentence": "She demonstrated remarkable courage during the crisis.", "chineseTranslation": "她在危机中表现出非凡的勇气。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to show how something works or how to do something",
          "meaningCN": "示范；演示",
          "examples": [
            { "sentence": "The teacher demonstrated the experiment step by step.", "chineseTranslation": "老师一步步地演示了这个实验。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "diverse", "ipaAm": "/daɪˈvɜːrs/", "ipaBr": "/daɪˈvɜːs/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "very different from each other and of various kinds",
          "meaningCN": "多种多样的；形形色色的",
          "examples": [
            { "sentence": "The city attracts people from diverse cultural backgrounds.", "chineseTranslation": "这座城市吸引着来自不同文化背景的人们。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "emphasize", "ipaAm": "/ˈemfəsaɪz/", "ipaBr": "/ˈemfəsaɪz/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to give special importance or attention to something",
          "meaningCN": "强调；着重",
          "examples": [
            { "sentence": "The manager emphasized the importance of teamwork.", "chineseTranslation": "经理强调了团队合作的重要性。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "enhance", "ipaAm": "/ɪnˈhæns/", "ipaBr": "/ɪnˈhɑːns/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to increase the quality, value, or beauty of something",
          "meaningCN": "提高；增强；增进",
          "examples": [
            { "sentence": "Good lighting can enhance the beauty of a room.", "chineseTranslation": "良好的照明能增添房间的美感。", "exampleOrder": 0 },
            { "sentence": "The software was updated to enhance security.", "chineseTranslation": "软件已更新以增强安全性。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "establish", "ipaAm": "/ɪˈstæblɪʃ/", "ipaBr": "/ɪˈstæblɪʃ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to start or create an organization, system, or relationship that is meant to last",
          "meaningCN": "建立；创立；设立",
          "examples": [
            { "sentence": "The university was established in 1851.", "chineseTranslation": "这所大学创立于 1851 年。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to show or prove that something is true",
          "meaningCN": "证实；确定",
          "examples": [
            { "sentence": "The researchers established the cause of the disease.", "chineseTranslation": "研究人员确定了这种疾病的病因。", "exampleOrder": 0 },
            { "sentence": "His innocence was finally established in court.", "chineseTranslation": "他的清白最终在法庭上得到证实。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "evaluate", "ipaAm": "/ɪˈvæljueɪt/", "ipaBr": "/ɪˈvæljueɪt/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to judge how good, useful, or successful something is",
          "meaningCN": "评价；评估",
          "examples": [
            { "sentence": "We need to evaluate the results before drawing conclusions.", "chineseTranslation": "下结论之前我们需要评估这些结果。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "evident", "ipaAm": "/ˈevɪdənt/", "ipaBr": "/ˈevɪdənt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "clear and easy to see or understand",
          "meaningCN": "明显的；显而易见的",
          "examples": [
            { "sentence": "It was evident that she had been crying.", "chineseTranslation": "很明显她哭过。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "expand", "ipaAm": "/ɪkˈspænd/", "ipaBr": "/ɪkˈspænd/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to become or make something larger in size, number, or importance",
          "meaningCN": "扩大；扩展；膨胀",
          "examples": [
            { "sentence": "The company plans to expand into Asian markets.", "chineseTranslation": "公司计划扩展到亚洲市场。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "facilitate", "ipaAm": "/fəˈsɪlɪteɪt/", "ipaBr": "/fəˈsɪlɪteɪt/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to make an action or process easier",
          "meaningCN": "促进；使便利",
          "examples": [
            { "sentence": "The new bridge facilitates travel between the two towns.", "chineseTranslation": "新大桥使两镇之间的往来更加便利。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "feasible", "ipaAm": "/ˈfiːzəbl/", "ipaBr": "/ˈfiːzəbl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "possible and practical to do easily or conveniently",
          "meaningCN": "可行的；行得通的",
          "examples": [
            { "sentence": "The committee agreed that the plan was technically feasible.", "chineseTranslation": "委员会认为该计划在技术上是可行的。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "flourish", "ipaAm": "/ˈflɜːrɪʃ/", "ipaBr": "/ˈflʌrɪʃ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to develop quickly and be successful or healthy",
          "meaningCN": "繁荣；兴旺；茁壮成长",
          "examples": [
            { "sentence": "The economy flourished during that decade.", "chineseTranslation": "那个十年间经济繁荣发展。", "exampleOrder": 0 },
            { "sentence": "These plants flourish in warm, humid conditions.", "chineseTranslation": "这些植物在温暖潮湿的环境中长势旺盛。", "exampleOrder": 1 }
          ] },
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "a bold or dramatic gesture done to attract attention",
          "meaningCN": "（引人注目的）夸张动作；挥舞",
          "examples": [
            { "sentence": "He opened the door with a flourish.", "chineseTranslation": "他夸张地一下子打开了门。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "generate", "ipaAm": "/ˈdʒenəreɪt/", "ipaBr": "/ˈdʒenəreɪt/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to produce or create something, especially power, interest, or income",
          "meaningCN": "产生；引起（电力、兴趣、收入等）",
          "examples": [
            { "sentence": "The wind farm generates enough power for thirty thousand homes.", "chineseTranslation": "这座风电场可为三万户家庭供电。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "genuine", "ipaAm": "/ˈdʒenjuɪn/", "ipaBr": "/ˈdʒenjuɪn/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "real and exactly what it appears to be; sincere",
          "meaningCN": "真正的；真诚的",
          "examples": [
            { "sentence": "The painting was confirmed to be a genuine Van Gogh.", "chineseTranslation": "这幅画被证实是梵高的真迹。", "exampleOrder": 0 },
            { "sentence": "She showed genuine concern for our wellbeing.", "chineseTranslation": "她对我们的健康表现出真诚的关心。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "implement", "ipaAm": "/ˈɪmplɪment/", "ipaBr": "/ˈɪmplɪment/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to put a plan, decision, or system into effect",
          "meaningCN": "实施；执行；贯彻",
          "examples": [
            { "sentence": "The new policy will be implemented next month.", "chineseTranslation": "新政策将于下个月实施。", "exampleOrder": 0 },
            { "sentence": "It took a year to implement the safety system.", "chineseTranslation": "部署这套安全系统花了整整一年。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "imply", "ipaAm": "/ɪmˈplaɪ/", "ipaBr": "/ɪmˈplaɪ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to suggest something indirectly rather than state it explicitly",
          "meaningCN": "暗示；含有…的意思",
          "examples": [
            { "sentence": "His silence seemed to imply agreement.", "chineseTranslation": "他的沉默似乎暗示着同意。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "inevitable", "ipaAm": "/ɪnˈevɪtəbl/", "ipaBr": "/ɪnˈevɪtəbl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "certain to happen and impossible to avoid",
          "meaningCN": "不可避免的；必然发生的",
          "examples": [
            { "sentence": "Reform was inevitable once the crisis began.", "chineseTranslation": "危机一旦开始，改革就不可避免。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "inclined", "ipaAm": "/ɪnˈklaɪnd/", "ipaBr": "/ɪnˈklaɪnd/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "tending to do something or likely to feel a particular way",
          "meaningCN": "倾向于…的；很有可能…的",
          "examples": [
            { "sentence": "I am inclined to believe her version of the story.", "chineseTranslation": "我倾向于相信她对这件事的说法。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "insight", "ipaAm": "/ˈɪnsaɪt/", "ipaBr": "/ˈɪnsaɪt/",
      "definitions": [
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "a deep and clear understanding of something complicated",
          "meaningCN": "洞察力；深刻见解",
          "examples": [
            { "sentence": "Her essay offers fresh insight into the poet's mind.", "chineseTranslation": "她的文章对诗人的内心提出了新的见解。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "interpret", "ipaAm": "/ɪnˈtɜːrprɪt/", "ipaBr": "/ɪnˈtɜːprɪt/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to explain the meaning of something",
          "meaningCN": "解释；阐释",
          "examples": [
            { "sentence": "Historians interpret the same events in different ways.", "chineseTranslation": "历史学家以不同方式解读同样的事件。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to translate one language into another, especially orally",
          "meaningCN": "口译",
          "examples": [
            { "sentence": "She interpreted the minister's speech for the foreign guests.", "chineseTranslation": "她为外宾口译了部长的讲话。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "magnitude", "ipaAm": "/ˈmæɡnɪtuːd/", "ipaBr": "/ˈmæɡnɪtjuːd/",
      "definitions": [
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "the great size or importance of something; a number that measures size",
          "meaningCN": "巨大；重要性；（数学）量级",
          "examples": [
            { "sentence": "They failed to grasp the magnitude of the problem.", "chineseTranslation": "他们没有意识到问题的严重性。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "meticulous", "ipaAm": "/məˈtɪkjələs/", "ipaBr": "/məˈtɪkjələs/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "showing great attention to detail; very careful and precise",
          "meaningCN": "一丝不苟的；精细的",
          "examples": [
            { "sentence": "He kept meticulous records of every transaction.", "chineseTranslation": "他对每一笔交易都做了细致的记录。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "obtain", "ipaAm": "/əbˈteɪn/", "ipaBr": "/əbˈteɪn/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to get something, especially by making an effort",
          "meaningCN": "获得；得到",
          "examples": [
            { "sentence": "You must obtain permission before publishing the data.", "chineseTranslation": "发布这些数据前必须获得许可。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "obvious", "ipaAm": "/ˈɑːbviəs/", "ipaBr": "/ˈɒbviəs/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "easy to see or understand; clear to everybody",
          "meaningCN": "明显的；显然的",
          "examples": [
            { "sentence": "For obvious reasons, we cannot tell you the details.", "chineseTranslation": "出于显而易见的原因，我们不能告诉你细节。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "persistent", "ipaAm": "/pərˈsɪstənt/", "ipaBr": "/pəˈsɪstənt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "continuing to do something despite difficulty or opposition",
          "meaningCN": "坚持不懈的；执着的",
          "examples": [
            { "sentence": "Her persistent efforts finally paid off.", "chineseTranslation": "她坚持不懈的努力终于有了回报。", "exampleOrder": 0 },
            { "sentence": "The persistent rain delayed the match for hours.", "chineseTranslation": "连绵不断的雨使比赛推迟了几个小时。", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "pursue", "ipaAm": "/pərˈsuː/", "ipaBr": "/pəˈsjuː/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to follow or chase somebody or something in order to catch them",
          "meaningCN": "追赶；追捕",
          "examples": [
            { "sentence": "The police pursued the car along the highway.", "chineseTranslation": "警方沿着高速公路追赶那辆车。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to continue trying to achieve something over a long period",
          "meaningCN": "追求；致力于",
          "examples": [
            { "sentence": "She moved abroad to pursue her acting career.", "chineseTranslation": "她移居国外追求演艺事业。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "refine", "ipaAm": "/rɪˈfaɪn/", "ipaBr": "/rɪˈfaɪn/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to improve something by making small changes over time",
          "meaningCN": "改进；完善；精炼",
          "examples": [
            { "sentence": "The design was refined after months of user testing.", "chineseTranslation": "经过数月用户测试，设计得到了完善。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "relevant", "ipaAm": "/ˈreləvənt/", "ipaBr": "/ˈreləvənt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "closely connected to what is being discussed or considered",
          "meaningCN": "相关的；切题的",
          "examples": [
            { "sentence": "Please attach all relevant documents to your application.", "chineseTranslation": "请在申请后附上所有相关文件。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "reluctant", "ipaAm": "/rɪˈlʌktənt/", "ipaBr": "/rɪˈlʌktənt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "hesitant and unwilling to do something",
          "meaningCN": "不情愿的；勉强的",
          "examples": [
            { "sentence": "He was reluctant to admit that he had been wrong.", "chineseTranslation": "他不情愿承认自己错了。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "remarkable", "ipaAm": "/rɪˈmɑːrkəbl/", "ipaBr": "/rɪˈmɑːkəbl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "unusual or surprising in a way that deserves attention",
          "meaningCN": "非凡的；引人注目的",
          "examples": [
            { "sentence": "The patient made a remarkable recovery.", "chineseTranslation": "病人的康复情况好得出奇。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "resolve", "ipaAm": "/rɪˈzɑːlv/", "ipaBr": "/rɪˈzɒlv/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to solve a problem, difficulty, or disagreement",
          "meaningCN": "解决（问题、分歧）",
          "examples": [
            { "sentence": "The two sides finally resolved their differences through talks.", "chineseTranslation": "双方最终通过会谈解决了分歧。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 2,
          "meaningEN": "to make a firm decision to do something",
          "meaningCN": "下决心；决意",
          "examples": [
            { "sentence": "She resolved to study abroad after graduation.", "chineseTranslation": "她决意毕业后出国留学。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "firm determination to do something",
          "meaningCN": "决心；决断力",
          "examples": [
            { "sentence": "They held on with great resolve despite the setbacks.", "chineseTranslation": "尽管屡遭挫折，他们仍以极大的决心坚持下来。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "retain", "ipaAm": "/rɪˈteɪn/", "ipaBr": "/rɪˈteɪn/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to keep something or continue to have something",
          "meaningCN": "保留；保持",
          "examples": [
            { "sentence": "The village has retained much of its old character.", "chineseTranslation": "这个村庄保留了许多旧日风貌。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "significant", "ipaAm": "/sɪɡˈnɪfɪkənt/", "ipaBr": "/sɪɡˈnɪfɪkənt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "large or important enough to have an effect or be noticed",
          "meaningCN": "重要的；显著的",
          "examples": [
            { "sentence": "There was a significant rise in house prices last year.", "chineseTranslation": "去年房价显著上涨。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 2,
          "meaningEN": "having a particular meaning, often one that is not immediately obvious",
          "meaningCN": "别有含义的；意味深长的",
          "examples": [
            { "sentence": "He gave her a significant look before answering.", "chineseTranslation": "回答之前，他意味深长地看了她一眼。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "subject", "ipaAm": "/ˈsʌbdʒɪkt/", "ipaBr": "/ˈsʌbdʒɪkt/",
      "definitions": [
        { "partOfSpeech": "noun", "partOfSpeechOrder": 1, "definitionOrder": 1,
          "meaningEN": "a thing or person that is being discussed or dealt with",
          "meaningCN": "主题；话题；对象",
          "examples": [
            { "sentence": "She changed the subject when I asked about her job.", "chineseTranslation": "我问起她的工作时，她转换了话题。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "likely to be affected by something, especially something unpleasant",
          "meaningCN": "易受…影响的；取决于…的",
          "examples": [
            { "sentence": "Flight times are subject to change without notice.", "chineseTranslation": "航班时刻可能会不经通知而变更。", "exampleOrder": 0 }
          ] },
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to force somebody or something to experience something unpleasant",
          "meaningCN": "使经受；使遭受",
          "examples": [
            { "sentence": "The prisoners were subjected to harsh questioning.", "chineseTranslation": "囚犯们受到了严厉的盘问。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "subtle", "ipaAm": "/ˈsʌtl/", "ipaBr": "/ˈsʌtl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "not obvious or easy to notice; delicate and clever",
          "meaningCN": "不易察觉的；微妙的；精妙的",
          "examples": [
            { "sentence": "There is a subtle difference between the two words.", "chineseTranslation": "这两个词之间有微妙的差别。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "sufficient", "ipaAm": "/səˈfɪʃnt/", "ipaBr": "/səˈfɪʃnt/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "enough for a particular purpose; as much as is needed",
          "meaningCN": "足够的；充分的",
          "examples": [
            { "sentence": "We have sufficient evidence to support the conclusion.", "chineseTranslation": "我们有足够的证据支持这一结论。", "exampleOrder": 0 },
            { "sentence": "My grace is sufficient for thee: for my strength is made perfect in weakness.",
              "chineseTranslation": "我的恩典够你用的，因为我的能力是在人的软弱上显得完全。",
              "sourceType": "LICENSED_OTHER", "sourceRef": "KJV Bible (1611), 2 Corinthians 12:9", "licenseNote": "Public domain（1611 年钦定本，版权已过期）", "exampleOrder": 1 }
          ] }
      ] },
    {
      "text": "sustain", "ipaAm": "/səˈsteɪn/", "ipaBr": "/səˈsteɪn/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to make something continue for a period of time without breaking",
          "meaningCN": "维持；使持续",
          "examples": [
            { "sentence": "It is hard to sustain such a fast pace for an entire race.", "chineseTranslation": "整场比赛都保持这么快的节奏很难。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "transform", "ipaAm": "/trænsˈfɔːrm/", "ipaBr": "/trænsˈfɔːm/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to change something completely in form, character, or appearance",
          "meaningCN": "使改观；彻底转变",
          "examples": [
            { "sentence": "The Internet has transformed the way people communicate.", "chineseTranslation": "互联网彻底改变了人们的交流方式。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "verify", "ipaAm": "/ˈverɪfaɪ/", "ipaBr": "/ˈverɪfaɪ/",
      "definitions": [
        { "partOfSpeech": "verb", "partOfSpeechOrder": 0, "definitionOrder": 1,
          "meaningEN": "to check that something is true or accurate",
          "meaningCN": "核实；查证",
          "examples": [
            { "sentence": "Please verify your email address to activate the account.", "chineseTranslation": "请验证你的电子邮件地址以激活账户。", "exampleOrder": 0 }
          ] }
      ] },
    {
      "text": "vital", "ipaAm": "/ˈvaɪtl/", "ipaBr": "/ˈvaɪtl/",
      "definitions": [
        { "partOfSpeech": "adjective", "partOfSpeechOrder": 2, "definitionOrder": 1,
          "meaningEN": "absolutely necessary; essential for life or success",
          "meaningCN": "至关重要的；必不可少的",
          "examples": [
            { "sentence": "Vitamins play a vital role in maintaining health.", "chineseTranslation": "维生素在维持健康方面起着至关重要的作用。", "exampleOrder": 0 }
          ] }
      ] }
  ]
}
"""
