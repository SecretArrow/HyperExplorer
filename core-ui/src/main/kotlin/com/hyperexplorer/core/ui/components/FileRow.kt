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

package com.hyperexplorer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyperexplorer.core.model.FileCategory
import com.hyperexplorer.core.model.FileNode
import com.hyperexplorer.core.ui.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())

private fun iconFor(category: FileCategory): ImageVector =
    when (category) {
        FileCategory.FOLDER -> Icons.Filled.Folder
        FileCategory.IMAGE -> Icons.Filled.Image
        FileCategory.VIDEO -> Icons.Filled.Movie
        FileCategory.AUDIO -> Icons.Filled.MusicNote
        FileCategory.DOCUMENT -> Icons.Filled.Description
        FileCategory.ARCHIVE -> Icons.Filled.FolderZip
        FileCategory.APK -> Icons.Filled.Android
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

@Composable
private fun subtitleFor(node: FileNode): String =
    if (node.isDirectory) {
        stringResource(R.string.core_folder)
    } else {
        node.humanSize() + " · " + DATE_FORMAT.format(Date(node.lastModified))
    }

@Composable
fun FileRow(
    node: FileNode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val subtitle = subtitleFor(node)
    val label =
        buildFileRowLabel(
            isDirectory = node.isDirectory,
            name = node.name,
            subtitle = subtitle,
            typeWord = stringResource(if (node.isDirectory) R.string.core_folder else R.string.core_file),
        )
    val isSelected = selected
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    this.selected = isSelected
                }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(node.category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
