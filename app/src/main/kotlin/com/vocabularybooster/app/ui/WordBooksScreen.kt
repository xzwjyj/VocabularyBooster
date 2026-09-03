package com.vocabularybooster.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.WordBookSummary
import com.vocabularybooster.domain.model.WordBookType
import org.koin.androidx.compose.koinViewModel

/** 生词本管理（FR-4）：列表 + 创建 / 重命名 / 删除 + 进入本详情。 */
@Composable
fun WordBooksScreen(
    onBookClick: (Long) -> Unit,
    viewModel: WordBooksViewModel = koinViewModel(),
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WordBookSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<WordBookSummary?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("生词本", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { showCreateDialog = true }) { Text("新建") }
        }
        viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (viewModel.books.isEmpty()) {
            Text("还没有生词本，点「新建」创建一个", modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(viewModel.books, key = { it.wordBook.wordBookId }) { summary ->
                WordBookRow(
                    summary = summary,
                    onClick = { onBookClick(summary.wordBook.wordBookId) },
                    onRename = { renameTarget = summary },
                    onDelete = { deleteTarget = summary },
                )
                HorizontalDivider()
            }
        }
    }

    if (showCreateDialog) {
        NameEditorDialog(
            title = "新建生词本",
            initial = "",
            onConfirm = { viewModel.createBook(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }
    renameTarget?.let { target ->
        NameEditorDialog(
            title = "重命名「${target.wordBook.name}」",
            initial = target.wordBook.name,
            onConfirm = { viewModel.renameBook(target.wordBook.wordBookId, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.wordBook.name}」？") },
            text = { Text("只删除该生词本及其学习数据，词库中的单词不受影响。") },
            confirmButton = {
                val targetId = target.wordBook.wordBookId
                TextButton(onClick = { viewModel.deleteBook(targetId); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WordBookRow(
    summary: WordBookSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
    ) {
        Text(summary.wordBook.name, style = MaterialTheme.typography.titleMedium)
        val typeLabel = if (summary.wordBook.type == WordBookType.DERIVED) " · 派生" else ""
        Text(
            "${summary.entryCount} 词$typeLabel",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = onRename) { Text("改名") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun NameEditorDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 本内词条：列表 + 移除（FR-4）。 */
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: BookDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
        }
        Text(
            "本内词条",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        viewModel.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (viewModel.words.isEmpty()) {
            Text("本内还没有词", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(viewModel.words, key = { it.wordBookEntryId }) { word ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(word.wordText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.removeWord(bookId, word.wordId) }) { Text("移除") }
                }
                HorizontalDivider()
            }
        }
    }
}
