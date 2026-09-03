package com.vocabularybooster.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.DefinitionWithExamples
import com.vocabularybooster.domain.model.ExampleSourceType
import com.vocabularybooster.domain.model.WordDetail
import com.vocabularybooster.domain.model.groupedByPartOfSpeech
import org.koin.androidx.compose.koinViewModel

/** 词条详情（FR-2：POS 分组连续 + (POSOrder, defOrder) 排序 + EN 先 CN）。 */
@Composable
fun WordDetailScreen(
    wordText: String,
    onBack: () -> Unit,
    viewModel: WordDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(wordText) { viewModel.load(wordText) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val detail = viewModel.detail

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
        }
        if (detail == null) {
            Text(
                if (viewModel.loadFailed) "未收录该词" else "加载中…",
                modifier = Modifier.padding(16.dp),
            )
            return
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .testTag("word_detail_list"),
        ) {
            item(key = "word-${detail.word.wordId}") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(detail.word.text, style = MaterialTheme.typography.headlineMedium)
                    detail.word.ipaAm?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            detail.groupedByPartOfSpeech().forEach { group ->
                item(key = "pos-${detail.word.wordId}-${group.partOfSpeech}") {
                    Text(
                        group.partOfSpeech.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(
                    count = group.definitions.size,
                    key = { "def-${group.definitions[it].entry.definitionEntryId}" },
                ) { index ->
                    DefinitionBlock(group.definitions[index])
                    HorizontalDivider()
                }
            }
        }
        Button(
            onClick = { showSaveDialog = true },
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("save_to_book_button"),
        ) { Text("添加到生词本") }
    }

    if (showSaveDialog) {
        SaveToWordBookDialog(
            viewModel = viewModel,
            onDismiss = { showSaveDialog = false },
        )
        LaunchedEffect(viewModel.saved) {
            if (viewModel.saved) {
                showSaveDialog = false
            }
        }
    }
}

/** 一条释义：EN（粗体）→ CN → 例句（句子 → 译文，I-6/I-7 顺序）。 */
@Composable
private fun DefinitionBlock(definition: DefinitionWithExamples) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(definition.entry.meaningEN, style = MaterialTheme.typography.bodyLarge)
        Text(definition.entry.meaningCN, style = MaterialTheme.typography.bodyMedium)
        definition.examples.forEach { example ->
            Column(Modifier.padding(top = 6.dp, start = 12.dp)) {
                Text(example.sentence, style = MaterialTheme.typography.bodyMedium)
                Text(example.chineseTranslation, style = MaterialTheme.typography.bodySmall)
                Text(
                    "来源：${sourceTypeLabel(example.sourceType)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun sourceTypeLabel(type: ExampleSourceType): String = when (type) {
    ExampleSourceType.REAL_MOVIE_TV -> "影视"
    ExampleSourceType.CELEBRITY_SPEECH -> "演讲"
    ExampleSourceType.TED -> "TED"
    ExampleSourceType.AUDIOBOOK -> "有声书"
    ExampleSourceType.LICENSED_OTHER -> "授权/公版"
    ExampleSourceType.TTS -> "TTS"
}
