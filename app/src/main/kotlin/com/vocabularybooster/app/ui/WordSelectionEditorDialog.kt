package com.vocabularybooster.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.groupedByPartOfSpeech
import org.koin.androidx.compose.koinViewModel

/**
 * 词条选择编辑（FR-17，Phase 8.5）：渲染该词全部词库释义/例句（FR-2 排序，与查词详情同源），
 * 预填当前已保存选择；保存 = 单事务整组替换（掌握与队列位置不受影响，shared 层保证）。
 * 导入词（无词库释义）→ 空态提示。渲染结构与 SaveToWordBookDialog 同风格（去选本段）。
 */
@Composable
fun WordSelectionEditorDialog(
    bookId: Long,
    wordId: Long,
    wordText: String,
    onDismiss: () -> Unit,
    viewModel: WordSelectionEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId, wordId) { viewModel.load(bookId, wordId, wordText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑「$wordText」的选择") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val detail = viewModel.detail
                if (!viewModel.loaded) {
                    Text("加载中…")
                } else if (detail == null || detail.entries.isEmpty()) {
                    // 导入词（无词库释义，FR-14）：无选项可勾
                    Text("该词暂无词库释义可编辑")
                } else {
                    detail.groupedByPartOfSpeech().forEach { group ->
                        group.definitions.forEach { definition ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = definition.entry.definitionEntryId in viewModel.selectedDefinitions,
                                    onCheckedChange = {
                                        viewModel.toggleDefinition(definition.entry.definitionEntryId)
                                    },
                                    modifier = Modifier.testTag("edit_def_check_${definition.entry.definitionEntryId}"),
                                )
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text(
                                        "[${definition.entry.partOfSpeech}] ${definition.entry.meaningEN}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(definition.entry.meaningCN, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (definition.entry.definitionEntryId in viewModel.selectedDefinitions) {
                                definition.examples.forEach { example ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 24.dp),
                                    ) {
                                        val entryId = definition.entry.definitionEntryId
                                        Checkbox(
                                            checked = example.exampleId in
                                                viewModel.selectedExamples[entryId].orEmpty(),
                                            onCheckedChange = {
                                                viewModel.toggleExample(entryId, example.exampleId)
                                            },
                                            modifier = Modifier.testTag("edit_ex_check_${example.exampleId}"),
                                        )
                                        Text(example.sentence, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                viewModel.message?.let {
                    Text(
                        it,
                        color = if (viewModel.saved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::save,
                enabled = viewModel.loaded && viewModel.detail != null &&
                    viewModel.detail!!.entries.isNotEmpty(),
                modifier = Modifier.testTag("edit_save_button"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
