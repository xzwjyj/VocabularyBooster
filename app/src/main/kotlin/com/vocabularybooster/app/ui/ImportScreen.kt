package com.vocabularybooster.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vocabularybooster.importing.ImportReport
import com.vocabularybooster.importing.ParsedLine
import com.vocabularybooster.importing.PreviewLine
import org.koin.androidx.compose.koinViewModel

/**
 * TXT 导入（FR-14，Phase 7，IMPORT_SPEC §5）：五态全屏子页——
 * 选文件(SAF) → 检测+预览(20 行合法/非法标记) → 选本(已有/新建) → 导入中(行数+可取消) → 报告。
 * UI 只渲染状态 + 转发意图（ARCHITECTURE §4）。
 */
@Composable
fun ImportScreen(
    onDone: () -> Unit,
    viewModel: ImportViewModel = koinViewModel(),
) {
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.onFilePicked(uri) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导入 TXT 词表",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text("关闭") }
        }
        when (val current = viewModel.state) {
            is ImportUiState.PickFile -> PickFileContent(onPickFile = {
                pickFileLauncher.launch(arrayOf("text/plain", "application/octet-stream"))
            })
            is ImportUiState.Preview -> PreviewContent(
                encodingLabel = current.encodingLabel,
                lines = current.lines,
                viewModel = viewModel,
            )
            is ImportUiState.Importing -> ImportingContent(current, onCancel = { viewModel.cancelImport() })
            is ImportUiState.Report -> ReportContent(current.report, onDone = {
                viewModel.reset()
                onDone()
            })
            is ImportUiState.Failed -> FailedContent(current.message, onBack = { viewModel.reset() })
        }
    }
}

@Composable
private fun PickFileContent(onPickFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text("每行一个词或短语，可补充词性或释义（Tab 或逗号分隔），支持 UTF-8 与 GB18030。")
        Text("多词短语（如 roll out）整行书写即可；短语 + 释义请用 Tab 或逗号分隔。", modifier = Modifier.padding(top = 8.dp))
        Text("只写单词：导入词典中该词的全部中英文释义和例句。")
        Text("写词性（n./v./vt./vi./adj./adv./prep./int.）：只导入该词性的释义和例句，后面还可再写释义进一步筛选。")
        Text("重复词会自动去重；已有释义的词不会被覆盖。", modifier = Modifier.padding(top = 8.dp))
        Button(
            onClick = onPickFile,
            modifier = Modifier.padding(top = 24.dp).testTag("import_pick_file"),
        ) { Text("选择 TXT 文件") }
    }
}

@Composable
private fun PreviewContent(
    encodingLabel: String,
    lines: List<PreviewLine>,
    viewModel: ImportViewModel,
) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text("编码：$encodingLabel", style = MaterialTheme.typography.bodyMedium)
        Text("前 ${lines.size} 行预览（√ 合法 / × 非法）：", modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
            items(lines) { line ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (mark, color) = when (line.kind) {
                        is ParsedLine.WordOnly, is ParsedLine.WordWithTranslation ->
                            "√" to MaterialTheme.colorScheme.primary
                        ParsedLine.Ignored -> "空" to MaterialTheme.colorScheme.outline
                        is ParsedLine.Invalid -> "×" to MaterialTheme.colorScheme.error
                    }
                    Text(mark, color = color, modifier = Modifier.padding(end = 8.dp))
                    Text(line.raw, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                HorizontalDivider()
            }
        }
        Text("导入到：", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = !viewModel.createNewBook,
                    role = Role.RadioButton,
                    onClick = { viewModel.createNewBook = false },
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = !viewModel.createNewBook, onClick = null)
                Text("已有生词本", modifier = Modifier.padding(start = 8.dp))
            }
            if (!viewModel.createNewBook) {
                viewModel.books.forEach { summary ->
                    val bookId = summary.wordBook.wordBookId
                    Row(
                        Modifier.fillMaxWidth().padding(start = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = viewModel.selectedBookId == bookId,
                            onClick = { viewModel.selectedBookId = bookId },
                        )
                        Text("${summary.wordBook.name}（${summary.entryCount} 词）")
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = viewModel.createNewBook,
                    role = Role.RadioButton,
                    onClick = { viewModel.createNewBook = true },
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = viewModel.createNewBook, onClick = null)
                Text("新建生词本", modifier = Modifier.padding(start = 8.dp))
            }
            if (viewModel.createNewBook) {
                OutlinedTextField(
                    value = viewModel.newBookName,
                    onValueChange = { viewModel.newBookName = it },
                    label = { Text("新本名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                )
            }
        }
        viewModel.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(onClick = { viewModel.reset() }) { Text("重选文件") }
            Button(
                onClick = { viewModel.startImport() },
                modifier = Modifier.padding(start = 8.dp).testTag("import_start"),
            ) { Text("开始导入") }
        }
    }
}

@Composable
private fun ImportingContent(state: ImportUiState.Importing, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "已读取 ${state.linesRead} 行 · 新增 ${state.imported} 词" +
                (if (state.invalid > 0) " · 非法 ${state.invalid} 行" else ""),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text("导入中可以随时取消，取消后目标生词本保持原样。", style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = onCancel,
            modifier = Modifier.padding(top = 16.dp).testTag("import_cancel"),
        ) { Text("取消导入") }
    }
}

@Composable
private fun ReportContent(report: ImportReport, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text("导入完成", style = MaterialTheme.typography.titleLarge)
        val rows = buildList {
            add("有效行" to report.totalLines)
            add("新增词条" to report.imported)
            add("复用已有词" to report.reusedWords)
            add("文件内重复" to report.duplicatesInFile)
            add("本内已存在" to report.duplicatesInBook)
            add("补写释义" to report.updated)
            add("非法行" to report.invalid)
            if (report.enriched > 0) { // SCR-TXTDICTENRICH：富化成功才展示（退化路径零噪音）
                add("词典富化" to report.enriched)
                add("其中按释义匹配" to report.enrichedMatched)
            }
        }
        rows.forEach { (label, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, modifier = Modifier.weight(1f))
                Text(count.toString())
            }
        }
        if (report.invalidSamples.isNotEmpty()) {
            Text("非法行样例：", modifier = Modifier.padding(top = 12.dp))
            report.invalidSamples.forEach { sample ->
                Text("· $sample", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp).testTag("import_done")) {
            Text("完成")
        }
    }
}

@Composable
private fun FailedContent(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Text(
            "目标生词本未发生任何变化。",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("返回") }
    }
}
