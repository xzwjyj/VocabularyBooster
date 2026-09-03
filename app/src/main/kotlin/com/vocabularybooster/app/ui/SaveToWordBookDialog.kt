package com.vocabularybooster.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.groupedByPartOfSpeech

/** 保存流（FR-5 v1.3）：选本（多选/新建）→ 勾释义 → 逐条勾例句。 */
@Composable
fun SaveToWordBookDialog(
    viewModel: WordDetailViewModel,
    onDismiss: () -> Unit,
) {
    val detail = viewModel.detail ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加「${detail.word.text}」到生词本") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("选择生词本", style = MaterialTheme.typography.titleSmall)
                viewModel.books.forEach { summary ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = summary.wordBook.wordBookId in viewModel.selectedBooks,
                            onCheckedChange = { viewModel.toggleBook(summary.wordBook.wordBookId) },
                            modifier = Modifier.testTag("book_check_${summary.wordBook.wordBookId}"),
                        )
                        Text("${summary.wordBook.name}（${summary.entryCount} 词）")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = viewModel.newBookName,
                        onValueChange = viewModel::onNewBookNameChange,
                        modifier = Modifier.weight(1f).testTag("new_book_name"),
                        label = { Text("新建生词本") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = viewModel::createBookAndSelect,
                        modifier = Modifier.testTag("create_book_button"),
                    ) { Text("创建") }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("选择释义", style = MaterialTheme.typography.titleSmall)

                detail.groupedByPartOfSpeech().forEach { group ->
                    group.definitions.forEach { definition ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = definition.entry.definitionEntryId in viewModel.selectedDefinitions,
                                onCheckedChange = { viewModel.toggleDefinition(definition.entry.definitionEntryId) },
                                modifier = Modifier.testTag("def_check_${definition.entry.definitionEntryId}"),
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
                                        modifier = Modifier.testTag("ex_check_${example.exampleId}"),
                                    )
                                    Text(example.sentence, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                            }
                        }
                    }
                }
                viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::save,
                modifier = Modifier.testTag("confirm_save_button"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
