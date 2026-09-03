package com.vocabularybooster.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vocabularybooster.app.ui.BookDetailScreen
import com.vocabularybooster.app.ui.LookupScreen
import com.vocabularybooster.app.ui.WordBooksScreen
import com.vocabularybooster.app.ui.WordDetailScreen

/**
 * Phase 2 主界面：两 Tab（查词 / 生词本）+ 词条详情 / 本详情覆盖层。
 * 简单状态导航（无 nav 依赖）；UI 只渲染状态 + 转发意图（ARCHITECTURE §4）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { VocabularyBoosterRoot() }
            }
        }
    }
}

@Composable
private fun VocabularyBoosterRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openWordText by rememberSaveable { mutableStateOf<String?>(null) }
    var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0 && openWordText == null,
                    onClick = { tab = 0; openWordText = null; openBookId = null },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("查词") },
                )
                NavigationBarItem(
                    selected = tab == 1 && openBookId == null,
                    onClick = { tab = 1; openWordText = null; openBookId = null },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("生词本") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                openWordText != null -> WordDetailScreen(
                    wordText = openWordText!!,
                    onBack = { openWordText = null },
                )
                openBookId != null -> BookDetailScreen(
                    bookId = openBookId!!,
                    onBack = { openBookId = null },
                )
                tab == 0 -> LookupScreen(onWordClick = { openWordText = it })
                else -> WordBooksScreen(onBookClick = { openBookId = it })
            }
        }
    }
}
