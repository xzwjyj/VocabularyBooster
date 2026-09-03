package com.vocabularybooster.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.Word
import org.koin.androidx.compose.koinViewModel

/** 查词（FR-1）：搜索框 + 前缀联想结果。 */
@Composable
fun LookupScreen(
    onWordClick: (String) -> Unit,
    viewModel: LookupViewModel = koinViewModel(),
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = viewModel.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("search_input"),
            label = { Text("查词") },
            placeholder = { Text("输入英文单词，如 boost") },
            singleLine = true,
        )
        val results = viewModel.results
        when {
            results.isEmpty() -> Text(
                if (viewModel.searched && viewModel.query.isNotBlank()) "未找到匹配的词" else "输入以搜索本地词库",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(results, key = { it.wordId }) { word ->
                    WordResultRow(word, onClick = { onWordClick(word.text) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun WordResultRow(word: Word, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_result_${word.text}")
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(word.text, style = MaterialTheme.typography.titleMedium)
        word.ipaAm?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
