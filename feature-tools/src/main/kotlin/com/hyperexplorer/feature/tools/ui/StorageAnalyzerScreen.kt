/*
 * Hyper Explorer - a free and open source file manager for Android.
 * Copyright (C) 2025-2026 Hyper Explorer contributors
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.hyperexplorer.feature.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileCategory
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.components.EmptyState
import com.hyperexplorer.core.ui.components.FileRow
import com.hyperexplorer.feature.tools.R
import com.hyperexplorer.feature.tools.storage.StorageAnalyzer
import com.hyperexplorer.feature.tools.storage.StorageReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class StorageUi(
    val loading: Boolean = false,
    val error: Boolean = false,
    val report: StorageReport? = null,
)

private val DISPLAY_CATEGORIES =
    listOf(
        FileCategory.IMAGE,
        FileCategory.VIDEO,
        FileCategory.AUDIO,
        FileCategory.DOCUMENT,
        FileCategory.ARCHIVE,
        FileCategory.APK,
    )

@Composable
private fun categoryLabel(category: FileCategory): String =
    when (category) {
        FileCategory.FOLDER -> stringResource(R.string.tools_category_folder)
        FileCategory.IMAGE -> stringResource(R.string.tools_category_image)
        FileCategory.VIDEO -> stringResource(R.string.tools_category_video)
        FileCategory.AUDIO -> stringResource(R.string.tools_category_audio)
        FileCategory.DOCUMENT -> stringResource(R.string.tools_category_document)
        FileCategory.ARCHIVE -> stringResource(R.string.tools_category_archive)
        FileCategory.APK -> stringResource(R.string.tools_category_apk)
        FileCategory.OTHER -> stringResource(R.string.tools_category_other)
    }

/**
 * State holder layar analisis penyimpanan: menjalankan [StorageAnalyzer] di
 * latar belakang dan mengekspos hasilnya sebagai [ui].
 */
class StorageAnalyzerState(
    private val root: File,
    private val analyzer: StorageAnalyzer = StorageAnalyzer(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ui = MutableStateFlow(StorageUi(loading = true))
    val ui: StateFlow<StorageUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _ui.update { it.copy(loading = true, error = false) }
        scope.launch {
            val report =
                try {
                    analyzer.analyze(root)
                } catch (t: Throwable) {
                    null
                }
            _ui.update { state ->
                if (report == null) {
                    state.copy(loading = false, error = true)
                } else {
                    state.copy(loading = false, report = report, error = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    root: File,
    onOpenFile: (File) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state = remember(root) { StorageAnalyzerState(root) }
    val ui by state.ui.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.tools_title)) },
                actions = {
                    IconButton(onClick = { state.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.tools_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val report = ui.report
            when {
                ui.error -> EmptyState(message = stringResource(R.string.tools_error_analysis_failed))
                report == null -> EmptyState(message = stringResource(R.string.tools_preparing_analysis))
                else -> AnalyzerContent(report = report, onOpenFile = onOpenFile)
            }
        }
    }
}

@Composable
private fun AnalyzerContent(
    report: StorageReport,
    onOpenFile: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item { SummaryCard(report = report) }
        item { CategorySection(report = report) }
        item { SectionTitle(text = stringResource(R.string.tools_largest_files_title)) }
        items(report.largestFiles, key = { it.path }) { node ->
            FileRow(
                node = node,
                selected = false,
                onClick = { onOpenFile(File(node.path)) },
            )
        }
        item { DuplicateSection(report = report) }
    }
}

@Composable
private fun SummaryCard(
    report: StorageReport,
    modifier: Modifier = Modifier,
) {
    val usedBytes = (report.totalBytes - report.usableBytes).coerceAtLeast(0L)
    val usedFraction = usedBytes.toFloat() / report.totalBytes.coerceAtLeast(1L)
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryItem(
                    label = stringResource(R.string.tools_summary_total),
                    value = FileNode.humanSize(report.totalBytes),
                    modifier = Modifier.weight(1f),
                )
                SummaryItem(
                    label = stringResource(R.string.tools_summary_used),
                    value = FileNode.humanSize(usedBytes),
                    modifier = Modifier.weight(1f),
                )
                SummaryItem(
                    label = stringResource(R.string.tools_summary_free),
                    value = FileNode.humanSize(report.usableBytes),
                    modifier = Modifier.weight(1f),
                )
            }
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CategorySection(
    report: StorageReport,
    modifier: Modifier = Modifier,
) {
    val visibleCategories = DISPLAY_CATEGORIES.filter { (report.categorySizes[it] ?: 0L) > 0L }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SectionTitle(text = stringResource(R.string.tools_categories_title), verticalPadding = 4.dp)
        if (visibleCategories.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_no_classified_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (category in visibleCategories) {
            CategoryRow(
                category = category,
                bytes = report.categorySizes[category] ?: 0L,
                totalBytes = report.totalBytes,
            )
        }
    }
}

@Composable
private fun CategoryRow(
    category: FileCategory,
    bytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val fraction = bytes.toFloat() / totalBytes.coerceAtLeast(1L)
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = categoryLabel(category),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = FileNode.humanSize(bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun DuplicateSection(
    report: StorageReport,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SectionTitle(text = stringResource(R.string.tools_duplicates_title), verticalPadding = 4.dp)
        if (report.duplicateGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_no_duplicates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (group in report.duplicateGroups) {
            DuplicateGroup(group = group)
        }
    }
}

@Composable
private fun DuplicateGroup(
    group: List<String>,
    modifier: Modifier = Modifier,
) {
    val groupSize = group.firstOrNull()?.let { File(it).length() } ?: 0L
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.tools_duplicate_group, FileNode.humanSize(groupSize), group.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        for (path in group) {
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 8.dp,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = verticalPadding),
    )
}
